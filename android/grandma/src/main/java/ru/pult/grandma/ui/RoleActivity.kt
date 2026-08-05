package ru.pult.grandma.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.PultApp

/**
 * Alpha 0.4 — единое приложение, две роли. При первом запуске человек выбирает, чем будет
 * этот телефон:
 *  - **Устройство** — то, которому помогают (фоновый сервис, показ экрана, обучение).
 *  - **Панель** — с которой помогают (встроенная веб-панель: трансляция, указатель, слайды,
 *    управление). Одна сборка — обе роли.
 *
 * Выбор запоминается; дальше приложение открывается сразу в нужной роли.
 */
class RoleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "Чем будет этот телефон?"
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        })

        root.addView(bigButton("Это устройство\nим помогают / управляют") {
            PultApp.setRole(this, PultApp.ROLE_DEVICE)
            PultApp.startDevice(applicationContext)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
        root.addView(spacer())
        root.addView(bigButton("Панель управления\nс неё помогают") {
            PultApp.setRole(this, PultApp.ROLE_PANEL)
            startActivity(Intent(this, PanelActivity::class.java))
            finish()
        })

        setContentView(root)
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(120))
    }

    private fun spacer() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(16))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
