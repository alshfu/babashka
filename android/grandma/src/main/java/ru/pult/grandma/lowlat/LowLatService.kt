package ru.pult.grandma.lowlat

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import ru.pult.core.lowlat.LowLatencyStreamer
import ru.pult.core.webrtc.WebRtcCore
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.PultApp
import ru.pult.grandma.control.RemoteControlService

/**
 * Прототип низколатентной трансляции (H.264 по WebSocket) — отдельный foreground-сервис
 * типа mediaProjection. Запускается из [LowLatActivity] после согласия на захват.
 *
 * Живёт рядом с обычным показом (WebRTC) и его не трогает: цель — замерить задержку
 * управления на scrcpy-подходе. Входящие команды панели отдаём в службу управления.
 */
class LowLatService : Service() {

    private var streamer: LowLatencyStreamer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS типа mediaProjection обязателен ДО getMediaProjection (Android 14+).
        startForeground(
            NOTIF_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) { stopSelf(); return START_NOT_STICKY }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val (w, h) = captureSize()
        val dpi = resources.displayMetrics.densityDpi

        streamer = LowLatencyStreamer(
            projection = projection,
            width = w, height = h, densityDpi = dpi,
            wsUrl = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: lowLatUrl(),
            onCommand = { text -> dispatch(text, w, h) },
            bitrate = (intent?.getIntExtra(EXTRA_BITRATE, 0) ?: 0).takeIf { it > 0 }
                ?: LowLatencyStreamer.DEFAULT_BITRATE,
            fps = (intent?.getIntExtra(EXTRA_FPS, 0) ?: 0).takeIf { it > 0 }
                ?: LowLatencyStreamer.DEFAULT_FPS,
        ).also { it.start() }

        return START_NOT_STICKY
    }

    /** Команда панели → служба управления. Координаты приходят в долях кадра. */
    private fun dispatch(text: String, w: Int, h: Int) {
        val svc = RemoteControlService.instance ?: return
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "tap" -> svc.tap(msg.optDouble("x").toFloat() * w, msg.optDouble("y").toFloat() * h)
            "swipe" -> svc.swipe(
                msg.optDouble("x1").toFloat() * w, msg.optDouble("y1").toFloat() * h,
                msg.optDouble("x2").toFloat() * w, msg.optDouble("y2").toFloat() * h,
                msg.optLong("ms", 300),
            )
            "nav" -> svc.nav(msg.optString("action"))
        }
    }

    override fun onDestroy() {
        streamer?.stop()
        streamer = null
        super.onDestroy()
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return WebRtcCore.Video.scale(metrics.widthPixels, metrics.heightPixels)
    }

    private fun lowLatUrl(): String {
        val base = BuildConfig.DEMO_SIGNALING_URL.removeSuffix("/ws")
        return "$base/lowlat?room=demo&role=device"
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, PultApp.CHANNEL_SESSION)
            .setContentTitle("Идёт показ экрана (быстрый режим)")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIF_ID = 42
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_URL = "url"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            bitrate: Int = 0,
            fps: Int = 0,
            url: String? = null,
        ) {
            val intent = Intent(context, LowLatService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_BITRATE, bitrate)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }
    }
}
