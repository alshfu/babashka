package ru.pult.grandma.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.grandma.R
import ru.pult.grandma.service.PultService
import ru.pult.grandma.setup.BrandSurvival
import ru.pult.grandma.setup.SetupStep

/**
 * Лицо службы — единственный экран приложения.
 *
 * Чего-то не хватает для жизни службы → короткий список только недостающих пунктов,
 * у каждого кнопка «Включить» ведёт сразу на нужный системный экран.
 * Всё включено → один тихий статус, без единого действия: открывать это окно
 * больше не нужно, служба живёт сама (FGS + автозагрузка + сторожа).
 */
class MainActivity : AppCompatActivity() {

    /** Один недостающий пункт: название, зачем нужен и куда ведёт кнопка. */
    private data class MissingItem(
        val title: String,
        val why: String,
        val openText: Int,
        val open: () -> Unit,
        val confirm: (() -> Unit)? = null,
    )

    // Пока открыт тихий статус, раз в пару секунд обновляем признак связи.
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (findViewById<View>(R.id.statusView).visibility == View.VISIBLE) renderLink()
            ticker.postDelayed(this, LINK_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        handlePultLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePultLink(intent)
    }

    /** pult://bankid-login?url=<bankid://…> — тестовый вход: сервисная цепочка, окно молчит. */
    private fun handlePultLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "pult" || uri.host != "bankid-login") return
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.startsWith("bankid:///")) {
            android.util.Log.i("PultControl", "pult://bankid-login → service")
            PultService.fireTestLogin(this, url)
        }
        // Не задерживаем пользователя на этом экране: окно тестового диплинка
        // — трамплин, назад уходим сами.
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        render()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val missing = missingItems()
        val missingView = findViewById<View>(R.id.missingView)
        val statusView = findViewById<View>(R.id.statusView)
        if (missing.isEmpty()) {
            missingView.visibility = View.GONE
            statusView.visibility = View.VISIBLE
            renderStatus()
        } else {
            statusView.visibility = View.GONE
            missingView.visibility = View.VISIBLE
            renderMissing(missing)
        }
    }

    /** Только то, чего прямо сейчас не хватает. Порядок — как в чек-листе настройки. */
    private fun missingItems(): List<MissingItem> = buildList {
        if (!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
            add(
                MissingItem(
                    title = getString(R.string.miss_notifications_title),
                    why = getString(R.string.miss_notifications_why),
                    openText = R.string.main_enable,
                    open = { open(SetupStep.Notifications.intent(this@MainActivity)) },
                ),
            )
        }
        if (!BrandSurvival.isBatteryOptimizationIgnored(this@MainActivity)) {
            add(
                MissingItem(
                    title = getString(R.string.miss_battery_title),
                    why = getString(R.string.miss_battery_why),
                    openText = R.string.main_enable,
                    open = { open(BrandSurvival.requestIgnoreBatteryOptimization(this@MainActivity)) },
                ),
            )
        }
        if (SetupStep.UsageAccess.granted(this@MainActivity) != true) {
            add(
                MissingItem(
                    title = getString(R.string.miss_usage_title),
                    why = getString(R.string.miss_usage_why),
                    openText = R.string.main_enable,
                    open = { open(SetupStep.UsageAccess.intent(this@MainActivity)) },
                ),
            )
        }
        if (!Settings.canDrawOverlays(this@MainActivity)) {
            add(
                MissingItem(
                    title = getString(R.string.miss_overlay_title),
                    why = getString(R.string.miss_overlay_why),
                    openText = R.string.main_enable,
                    open = { open(SetupStep.Overlay.intent(this@MainActivity)) },
                ),
            )
        }
        // Автозапуск программно не проверить — строка висит, пока человек не подтвердит.
        if (!setupPrefs().getBoolean(SetupStep.Autostart.id, false)) {
            add(
                MissingItem(
                    title = getString(R.string.miss_autostart_title, BrandSurvival.brandName),
                    why = getString(R.string.miss_autostart_why),
                    openText = R.string.setup_open,
                    open = {
                        open(BrandSurvival.firstResolvable(this@MainActivity, BrandSurvival.autostartIntents(this@MainActivity)))
                    },
                    confirm = {
                        setupPrefs().edit().putBoolean(SetupStep.Autostart.id, true).apply()
                        render()
                    },
                ),
            )
        }
        if (EncryptedPairStore(this@MainActivity).load() == null) {
            add(
                MissingItem(
                    title = getString(R.string.miss_pair_title),
                    why = getString(R.string.miss_pair_why),
                    openText = R.string.main_enable,
                    open = { startActivity(Intent(this@MainActivity, PairingActivity::class.java)) },
                ),
            )
        }
    }

    private fun renderMissing(missing: List<MissingItem>) {
        val list = findViewById<LinearLayout>(R.id.missingList)
        list.removeAllViews()
        for (item in missing) {
            val row = layoutInflater.inflate(R.layout.item_activation, list, false)
            row.findViewById<TextView>(R.id.title).text = item.title
            row.findViewById<TextView>(R.id.why).text = item.why
            row.findViewById<Button>(R.id.enable).apply {
                setText(item.openText)
                setOnClickListener { item.open() }
            }
            row.findViewById<Button>(R.id.confirm).apply {
                if (item.confirm == null) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                    setOnClickListener { item.confirm.invoke() }
                }
            }
            list.addView(row)
        }
    }

    /** Тихий статус: служба работает, связь есть/нет, кто помощник. Без действий. */
    private fun renderStatus() {
        val pair = EncryptedPairStore(this).load()
        findViewById<TextView>(R.id.helperState).text =
            if (pair == null) "" else getString(R.string.main_helper, pair.peerName)
        renderLink()
    }

    private fun renderLink() {
        val online = PultService.signalingOnline
        findViewById<TextView>(R.id.linkState).apply {
            setText(if (online) R.string.main_link_online else R.string.main_link_offline)
            setTextColor(if (online) 0xFF22C55E.toInt() else 0xFF888888.toInt())
        }
    }

    /** Открыть системный экран; конкретного Activity на прошивке может не быть — не падаем. */
    private fun open(intent: Intent?) {
        intent ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Те же префы, что и у мастера настройки: подтверждённый там шаг тут не всплывёт. */
    private fun setupPrefs() = getSharedPreferences("pult_setup", Context.MODE_PRIVATE)

    private companion object {
        const val LINK_POLL_MS = 2_000L
    }
}
