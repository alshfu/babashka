package ru.pult.grandma.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.vpn.PultVpnService
import ru.pult.grandma.vpn.TunnelPolicy

/**
 * Настройка split-tunnel: семья отмечает приложения, которым дать доступ через туннель.
 *
 * Банки и платёжные приложения показываются, но отмечены «— напрямую» и включить их нельзя
 * (TunnelPolicy.isExcluded). Так наглядно видно: банк идёт мимо туннеля, всегда.
 */
class VpnActivity : AppCompatActivity() {

    private val policy by lazy { TunnelPolicy(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(72), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Приложения через защищённый канал. Банки — всегда напрямую, их включить нельзя."
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        })

        val startBtn = Button(this).apply {
            text = "Включить доступ"
            textSize = 18f
            setOnClickListener { start() }
        }
        val stopBtn = Button(this).apply {
            text = "Выключить"
            textSize = 18f
            setOnClickListener {
                startService(Intent(this@VpnActivity, PultVpnService::class.java).setAction(PultVpnService.ACTION_STOP))
            }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(MATCH, dp(64)))
        root.addView(stopBtn, LinearLayout.LayoutParams(MATCH, dp(56)))

        val allowed = policy.allowedApps()
        val pm = packageManager
        pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null } // только запускаемые
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .forEach { app ->
                val pkg = app.packageName
                if (pkg == packageName) return@forEach
                val excluded = policy.isExcluded(pkg)
                root.addView(CheckBox(this).apply {
                    text = pm.getApplicationLabel(app).toString() + if (excluded) "  — напрямую (банк)" else ""
                    textSize = 16f
                    isChecked = pkg in allowed
                    isEnabled = !excluded // банк не включить
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) policy.allow(pkg) else policy.disallow(pkg)
                    }
                })
            }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        // Системное согласие на VPN. Вернёт Intent — показываем диалог; null — уже разрешено.
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN)
        else onVpnGranted()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) onVpnGranted()
    }

    private fun onVpnGranted() {
        startService(Intent(this, PultVpnService::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val REQ_VPN = 7001
    }
}
