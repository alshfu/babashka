package ru.pult.helper.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import ru.pult.helper.PresenceService
import ru.pult.helper.R
import ru.pult.helper.data.HelperSession

/**
 * Экран сессии: видео бабушки и указатель.
 *
 * Чего здесь нет и не будет в V1: кнопок «нажать за бабушку» (управление — модуль V2)
 * и кнопки записи экрана. Указатель считается в долях кадра, а не в пикселях окна:
 * разрешения устройств не совпадают, и в пикселях он попадёт мимо.
 */
class SessionActivity : AppCompatActivity() {

    private var binder: PresenceService.LocalBinder? = null
    private lateinit var video: SurfaceViewRenderer

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as PresenceService.LocalBinder
            binder?.transport?.attachRenderer(video)
            observe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)
        video = findViewById(R.id.video)

        bindService(Intent(this, PresenceService::class.java), connection, Context.BIND_AUTO_CREATE)

        video.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                binder?.session?.pointer(event.x / view.width, event.y / view.height)
                view.performClick()
            }
            true
        }

        findViewById<Button>(R.id.sayTap).setOnClickListener {
            binder?.session?.say(getString(R.string.say_tap))
        }
        findViewById<Button>(R.id.sayScroll).setOnClickListener {
            binder?.session?.say(getString(R.string.say_scroll))
        }
        findViewById<Button>(R.id.finish).setOnClickListener {
            binder?.session?.finish()
            finish()
        }
    }

    private fun observe() {
        val session = binder?.session ?: return
        lifecycleScope.launch {
            session.ui.collect { state ->
                when (state) {
                    // Гашение включает бабушка; помощник видит заглушку и отменить её не может.
                    is HelperSession.Ui.Watching ->
                        findViewById<View>(R.id.redacted).visibility =
                            if (state.redacted) View.VISIBLE else View.GONE

                    is HelperSession.Ui.Refused -> finish()
                    else -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        binder?.transport?.detachRenderer()
        runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
