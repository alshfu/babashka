package ru.pult.helper

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.pult.core.net.SignalingClient
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.core.pairing.PairRecord
import ru.pult.core.protocol.PairAuth
import ru.pult.core.protocol.Role
import ru.pult.helper.data.HelperSession
import ru.pult.helper.data.WebRtcViewerTransport

/**
 * Лёгкий сервис помощника: держит связь с сигналингом, чтобы видеть присутствие бабушки
 * и получать ответ на запрос, даже когда приложение свёрнуто.
 *
 * Никакого захвата экрана, оверлеев и статистики использования здесь нет — помощнику
 * они не нужны, и просить их приложение не должно.
 */
class PresenceService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val session: HelperSession? get() = this@PresenceService.session
        val signaling: SignalingClient? get() = this@PresenceService.signaling

        /** Экрану сессии нужен конкретный транспорт: к нему привязывается рендерер. */
        val transport: WebRtcViewerTransport? get() = this@PresenceService.transport
    }

    private val binder = LocalBinder()
    private var signaling: SignalingClient? = null
    private var session: HelperSession? = null
    private var transport: WebRtcViewerTransport? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(online = false))
        EncryptedPairStore(this).load()?.let(::connect)
    }

    private fun connect(pair: PairRecord) {
        val client = SignalingClient(
            url = pair.signalingUrl,
            identity = SignalingClient.Identity(
                pairId = pair.pairId,
                role = Role.HELPER,
                deviceId = deviceId(),
            ),
            scope = lifecycleScope,
        )
        val viewerTransport = WebRtcViewerTransport(this)
        val helperSession = HelperSession(
            pair = pair,
            signaling = client,
            transport = viewerTransport,
            scope = lifecycleScope,
        )
        signaling = client
        session = helperSession
        transport = viewerTransport

        lifecycleScope.launch { client.incoming.collect(helperSession::handle) }
        lifecycleScope.launch {
            client.peerOnline.collect { online ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(online))
            }
        }
        client.connect()
    }

    private fun notification(online: Boolean): Notification =
        NotificationCompat.Builder(this, HelperApp.CHANNEL_PRESENCE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(if (online) R.string.state_online else R.string.state_offline))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        signaling?.close()
        super.onDestroy()
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("pult_device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: PairAuth.newId(8).also {
            prefs.edit().putString("id", it).apply()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }
    }
}
