package ru.pult.helper.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.helper.PresenceService
import ru.pult.helper.R
import ru.pult.helper.data.HelperSession

/**
 * Панель помощника: видно, в сети ли бабушка, и одна кнопка «Помочь бабушке».
 *
 * Отказ показывается нейтрально и без побуждающих кнопок: интерфейс не должен
 * подталкивать помощника давить на бабушку (docs/android-helper.md §1).
 */
class MainActivity : AppCompatActivity() {

    private var binder: PresenceService.LocalBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as PresenceService.LocalBinder
            observe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pair = EncryptedPairStore(this).load()
        if (pair == null) {
            findViewById<TextView>(R.id.status).setText(R.string.no_pair)
        } else {
            PresenceService.start(this)
            bindService(Intent(this, PresenceService::class.java), connection, Context.BIND_AUTO_CREATE)
        }

        findViewById<Button>(R.id.ask).setOnClickListener {
            val note = findViewById<EditText>(R.id.note).text.toString()
            binder?.session?.requestHelp(note)
        }
        findViewById<Button>(R.id.journal).setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }
        findViewById<Button>(R.id.pairing).setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
    }

    private fun observe() {
        val presence = findViewById<TextView>(R.id.presence)
        val ask = findViewById<Button>(R.id.ask)
        val status = findViewById<TextView>(R.id.status)

        lifecycleScope.launch {
            binder?.signaling?.peerOnline?.collect { online ->
                presence.setText(if (online) R.string.state_online else R.string.state_offline)
                ask.isEnabled = online
            }
        }

        lifecycleScope.launch {
            binder?.session?.ui?.collect { state ->
                when (state) {
                    is HelperSession.Ui.Idle -> status.text = ""
                    is HelperSession.Ui.Waiting -> status.setText(R.string.waiting)
                    is HelperSession.Ui.Connecting -> status.text = "Соединяемся…"
                    is HelperSession.Ui.Watching -> startActivity(
                        Intent(this@MainActivity, SessionActivity::class.java),
                    )

                    is HelperSession.Ui.Refused -> status.setText(
                        when (state.reason) {
                            "declined" -> R.string.declined
                            "no-answer" -> R.string.no_answer
                            "auth-failed" -> R.string.auth_failed
                            else -> R.string.declined
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
