package ru.pult.pult_gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Удерживает процесс живым и держит /link-канал к серверу.
 *
 * Канал живёт ЗДЕСЬ, а не во Flutter-движке, сознательно: Swedbank запускает
 * bankid-интент с FLAG_ACTIVITY_NO_HISTORY, и activity шлюза (а вместе с ней
 * движок и его WebSocket) уничтожается через доли секунды после перехвата —
 * диплинк терялся, канал молчал до ручного открытия приложения. Сервис
 * неприкосновенен для этой механики: пока жив процесс, канал жив.
 *
 * Диплинк от activity прилетает сюда через [sendDeeplink]. Если сокет ещё не
 * поднялся — диплинк ждёт в [pendingUrl] и уходит сразу после коннекта.
 * Статусы подписания широковещанием [ACTION_STATUS], UI читает их из
 * SharedPreferences ('lastStatus').
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    // Канал /link — напрямую, без системного прокси (см. TunnelProxyNative).
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var online = false
    private var backoffMs = 1_000L
    private var pendingUrl: String? = null
    private var pendingDeviceId: String? = null
    private var tunnel: TunnelProxyNative? = null

    private val prefs: SharedPreferences
        get() = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundInternal()
        connect()
        tunnel = TunnelProxyNative(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        intent?.getStringExtra(EXTRA_URL)?.let { url ->
            if (url.startsWith("bankid:///")) {
                pendingUrl = url
                pendingDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                flushPending()
            }
        }
        intent?.getStringExtra(EXTRA_RAW)?.let { payload ->
            if (online) ws?.send(payload)
        }
        if (ws != null && !endpointMatchesCurrentPrefs()) {
            ws?.close(1000, "settings changed")
            tunnel?.stop()
            tunnel = TunnelProxyNative(this).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tunnel?.stop()
        ws?.close(1000, "service destroyed")
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Styrkanal", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pult är aktiv")
            .setContentText("Kanalen till enheterna i Sverige underhålls")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun endpointMatchesCurrentPrefs(): Boolean {
        val current = wsEndpoint() ?: return false
        return current == activeEndpoint
    }

    private var activeEndpoint: String? = null

    private fun wsEndpoint(): String? {
        val server = prefs.getString("flutter.server", null) ?: return null
        val pairId = prefs.getString("flutter.pairId", null) ?: return null
        val token = prefs.getString("flutter.token", null) ?: return null
        if (token.isEmpty()) return null
        return "$server/link?pairId=$pairId&token=$token"
    }

    private fun connect() {
        if (ws != null) return
        val endpoint = wsEndpoint()
        if (endpoint == null) {
            Log.i(TAG, "endpoint not ready, retry in 2s")
            handler.postDelayed({ connect() }, 2_000)
            return
        }
        Log.i(TAG, "connecting: ${endpoint.substringBefore("token=")}…")
        activeEndpoint = endpoint
        val request = Request.Builder().url(endpoint).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "onOpen")
                backoffMs = 1_000
                online = true
                publishState()
                flushPending()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("t")) {
                        "deeplink-ack" -> publishStatus(
                            stage = "sent",
                            ok = msg.optBoolean("delivered"),
                            err = if (msg.optBoolean("delivered")) "" else "den valda enheten är offline",
                        )
                        "deeplink-status" -> publishStatus(
                            stage = msg.optString("stage"),
                            ok = msg.optBoolean("ok"),
                            err = msg.optString("err"),
                        )
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "onFailure: ${t.message} (http=${response?.code})")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        online = false
        ws = null
        publishState()
        handler.postDelayed({
            if (ws == null) connect()
        }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(15_000)
    }

    private fun flushPending() {
        val url = pendingUrl ?: return
        val socket = ws
        if (!online || socket == null) return
        val payload = JSONObject().put("t", "deeplink").put("url", url)
        pendingDeviceId?.let { payload.put("deviceId", it) }
        socket.send(payload.toString())
        pendingUrl = null
        pendingDeviceId = null
    }

    private fun publishStatus(stage: String, ok: Boolean, err: String) {
        prefs.edit()
            .putString("lastStatus", JSONObject()
                .put("stage", stage)
                .put("ok", ok)
                .put("err", err)
                .toString())
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun publishState() {
        prefs.edit().putBoolean("linkOnline", online).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL_ID = "gateway_link"
        private const val EXTRA_URL = "deeplink_url"
        private const val EXTRA_DEVICE_ID = "deeplink_device_id"
        private const val EXTRA_RAW = "raw_json"
        private const val TAG = "PultLink"
        const val ACTION_STATUS = "ru.pult.pult_gateway.LINK_STATUS"

        /**
         * Запросить список A-app устройств с сервера.
         * Возвращает JSON-массив или пустой массив при ошибке.
         */
        fun fetchDevices(context: Context): JSONArray {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val server = prefs.getString("flutter.server", null) ?: return JSONArray()
            val token = prefs.getString("flutter.token", null) ?: return JSONArray()
            val pairId = prefs.getString("flutter.pairId", null) ?: return JSONArray()
            val base = when {
                server.startsWith("wss://") -> "https://" + server.removePrefix("wss://")
                server.startsWith("ws://") -> "http://" + server.removePrefix("ws://")
                else -> server
            }
            // Менеджмент-запрос к сигналингу идёт напрямую, не через туннельный прокси.
            val http = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$base/api/devices?pairId=$pairId")
                .header("x-agent-token", token)
                .build()
            return try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return JSONArray()
                    JSONArray(response.body?.string() ?: "[]")
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchDevices failed: ${e.message}")
                JSONArray()
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Диплинк от перехватившего activity — в очередь сервиса, не в движок. */
        fun sendDeeplink(context: Context, url: String, deviceId: String? = null) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Готовый JSON-кадр прямо в сокет (screencast on/off). */
        fun sendJson(context: Context, payload: String) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_RAW, payload)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
