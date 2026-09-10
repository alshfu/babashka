package ru.pult.pult_gateway

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Туннель «весь трафик через телефон» — в нативном сервисе, не во Flutter.
 * Движок спит/умирает в фоне (Swedbank поверх шлюза), а прокси нужен живым
 * ровно в этот момент: системный прокси телефона указывает на 127.0.0.1:8877.
 *
 * Протокол кадра идентичен Dart-версии (app/lib/tunnel.dart) и серверу:
 * [streamId:uint32 BE][op:uint8][payload]; op: 0=open ("host:port"), 1=data,
 * 2=close, 3=error. Подробности — server/src/tunnel.js.
 */
class TunnelProxyNative(private val context: Context) {

    companion object {
        private const val TAG = "PultTunnel"
        private const val OP_OPEN = 0
        private const val OP_DATA = 1
        private const val OP_CLOSE = 2
        private const val OP_ERROR = 3
        const val PORT = 8877
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val io = Executors.newCachedThreadPool()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var online = false
    @Volatile private var stopped = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var backoffMs = 1_000L
    private var nextStreamId = 1

    private val clients = ConcurrentHashMap<Int, Socket>()

    fun start() {
        stopped = false
        // Слушатель локального прокси — один раз за жизнь сервиса.
        if (serverSocket == null) {
            io.execute { acceptLoop() }
        }
        connect()
    }

    fun stop() {
        stopped = true
        online = false
        ws?.close(1000, "service destroyed")
        runCatching { serverSocket?.close() }
        serverSocket = null
        closeAllClients()
        publish()
    }

    // ── Локальный CONNECT-прокси ────────────────────────────────────────────

    private fun acceptLoop() {
        val server = try {
            ServerSocket()
        } catch (e: Exception) {
            Log.e(TAG, "bind failed: ${e.message}")
            return
        }
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", PORT))
        serverSocket = server
        Log.i(TAG, "proxy listening on 127.0.0.1:$PORT")
        while (!stopped) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            io.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        var streamId = -1
        try {
            client.tcpNoDelay = true
            client.soTimeout = 0
            val input = client.getInputStream()
            // Читаем заголовок CONNECT до CRLFCRLF, не забирая лишних байт тела.
            val header = ByteArray(4096)
            var n = 0
            var headerEnd = -1
            while (headerEnd < 0) {
                val read = input.read(header, n, header.size - n)
                if (read < 0) { client.close(); return }
                n += read
                headerEnd = findHeaderEnd(header, n)
                if (n >= header.size) { client.close(); return } // заголовок не влез — мусор
            }
            val request = String(header, 0, headerEnd, Charsets.UTF_8)
            val match = Regex("^CONNECT ([^:\\s]+):(\\d+) HTTP").find(request.lineSequence().first())
            if (match == null || !online) {
                // Туннель не поднят — честный отказ, а не чёрная дыра.
                client.getOutputStream().write(
                    "HTTP/1.1 ${if (online) "405" else "503"}\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray(),
                )
                client.close()
                return
            }
            streamId = nextStreamId++
            clients[streamId] = client
            publish()
            send(streamId, OP_OPEN, "${match.groupValues[1]}:${match.groupValues[2]}".toByteArray())
            // Оптимистичное 200 — как в Dart-версии: при недоступности цели придёт opError.
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            // Байты, приехавшие вместе с заголовком (пайплайнинг), не теряем.
            if (n > headerEnd) send(streamId, OP_DATA, header.copyOfRange(headerEnd, n))

            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                send(streamId, OP_DATA, buf.copyOf(read))
            }
        } catch (_: Exception) {
            // сокет умер — просто закрываем стрим
        } finally {
            if (streamId >= 0) {
                clients.remove(streamId)
                send(streamId, OP_CLOSE, ByteArray(0))
                publish()
            }
            runCatching { client.close() }
        }
    }

    private fun findHeaderEnd(buf: ByteArray, n: Int): Int {
        var i = 0
        while (i + 3 < n) {
            if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte()
                && buf[i + 2] == 13.toByte() && buf[i + 3] == 10.toByte()
            ) return i + 4
            i++
        }
        return -1
    }

    // ── WS к серверу ────────────────────────────────────────────────────────

    private fun endpoint(): String? {
        val server = prefs.getString("flutter.server", null) ?: return null
        val pairId = prefs.getString("flutter.pairId", null) ?: return null
        val token = prefs.getString("flutter.token", null) ?: return null
        if (token.isEmpty()) return null
        return "$server/tunnel?pairId=$pairId&token=$token&side=app"
    }

    private fun connect() {
        if (ws != null || stopped) return
        val url = endpoint()
        if (url == null) {
            handler().postDelayed({ connect() }, 2_000)
            return
        }
        Log.i(TAG, "connecting tunnel ws")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "tunnel online")
                backoffMs = 1_000
                online = true
                publish()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onFrame(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail("closed $code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t.message ?: "ws failure")
            }
        })
    }

    private fun fail(reason: String) {
        Log.w(TAG, "tunnel down: $reason (streams=${clients.size})")
        online = false
        ws = null
        // Все потоки мертвы: кадры некуда слать.
        closeAllClients()
        publish()
        if (stopped) return
        handler().postDelayed({ if (ws == null && !stopped) connect() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(15_000)
    }

    private fun handler() = android.os.Handler(android.os.Looper.getMainLooper())

    private fun closeAllClients() {
        for (s in clients.values) runCatching { s.close() }
        clients.clear()
    }

    private fun send(streamId: Int, op: Int, payload: ByteArray) {
        val socket = ws
        if (!online || socket == null) return
        val frame = ByteBuffer.allocate(5 + payload.size)
            .putInt(streamId)
            .put(op.toByte())
            .put(payload)
            .array()
        socket.send(frame.toByteString())
    }

    private fun onFrame(frame: ByteArray) {
        if (frame.size < 5) return
        val buf = ByteBuffer.wrap(frame)
        val streamId = buf.int
        val op = buf.get().toInt()
        val payload = ByteArray(buf.remaining()).also { buf.get(it) }
        val client = clients[streamId] ?: return
        when (op) {
            OP_DATA -> io.execute {
                try {
                    client.getOutputStream().write(payload)
                    client.getOutputStream().flush()
                } catch (_: Exception) {
                    clients.remove(streamId)
                    runCatching { client.close() }
                    publish()
                }
            }
            OP_CLOSE, OP_ERROR -> {
                clients.remove(streamId)
                // Graceful close: сперва flush не дочитанного, потом FIN.
                io.execute { runCatching { client.shutdownOutput(); client.close() } }
                publish()
            }
        }
    }

    private fun publish() {
        prefs.edit()
            // flutter.-префикс: shared_preferences на Dart-стороне читает ключи так.
            .putBoolean("flutter.tunnelOnline", online)
            .putInt("flutter.tunnelStreams", clients.size)
            .apply()
        context.sendBroadcast(Intent(KeepAliveService.ACTION_STATUS).setPackage(context.packageName))
    }
}
