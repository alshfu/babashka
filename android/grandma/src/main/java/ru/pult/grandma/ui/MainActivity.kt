package ru.pult.grandma.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.grandma.R
import ru.pult.grandma.service.PultService

/**
 * Лицо службы — единственный экран приложения.
 *
 * Один тихий статус, без единого действия: служба работает, связь есть/нет, кто
 * помощник. Открывать это окно больше не нужно, служба живёт сама (FGS + автозагрузка
 * + сторожа). Чек-лист настройки уехал в приложение шлюза — оно ведёт её кадрами
 * setup-open/setup-query по каналу /link (PultService.onSetupOpen).
 */
class MainActivity : AppCompatActivity() {

    // Пока открыт тихий статус, раз в пару секунд обновляем признак связи.
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderLink()
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

    /**
     * Локальные диплинки-исключения для отладки без сервера:
     * pult://bankid-login?url=<bankid://…> — тестовый вход: сервисная цепочка, окно молчит;
     * pult://setup?step=<id> — тот же вход, что setup-open из /link (PultService.openSetupStep).
     * Окно — трамплин: не задерживаем пользователя, назад уходим сами.
     */
    private fun handlePultLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "pult") return
        when (uri.host) {
            "bankid-login" -> {
                val url = uri.getQueryParameter("url").orEmpty()
                if (url.startsWith("bankid:///")) {
                    android.util.Log.i("PultControl", "pult://bankid-login → service")
                    PultService.fireTestLogin(this, url)
                }
            }
            "setup" -> {
                val step = uri.getQueryParameter("step").orEmpty()
                android.util.Log.i("PultControl", "pult://setup step=$step → service")
                PultService.openSetupStep(this, step)
            }
            else -> return
        }
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
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

    private companion object {
        const val LINK_POLL_MS = 2_000L
    }
}
