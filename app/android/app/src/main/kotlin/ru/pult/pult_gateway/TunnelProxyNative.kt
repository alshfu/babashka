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

    // Сигналинг идёт напрямую, не через системный прокси (туннель): иначе при
    // падении туннеля переподключение пошло бы через мёртвый прокси — deadlock.
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
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
            val firstLine = request.lineSequence().first()
            val pipelined = if (n > headerEnd) header.copyOfRange(headerEnd, n) else null

            // CONNECT host:port (HTTPS и прочий TCP через прокси).
            val connect = Regex("^CONNECT ([^:\\s]+):(\\d+) HTTP").find(firstLine)
            // Абсолютный URI (проксируемый plain HTTP — WebView/браузер на http://).
            val plain = Regex("^([A-Z]+) http://([^/\\s:]+)(?::(\\d+))?(\\S*) HTTP").find(firstLine)

            if (connect == null && plain == null) {
                client.getOutputStream().write("HTTP/1.1 405\r\nContent-Length: 0\r\n\r\n".toByteArray())
                client.close()
                return
            }

            if (connect != null) {
                val host = connect.groupValues[1]
                val port = connect.groupValues[2].toIntOrNull() ?: 443
                // Управляющий трафик к сигналингу (API устройств, lowlat-страница, /link)
                // не должен уходить в туннель: цель живёт рядом с B-app, а не с A-app.
                if (isDirectTarget(host)) {
                    relayDirect(
                        client, host, port,
                        clientPrefix = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(),
                        upstreamPrefix = pipelined,
                    )
                    return
                }
                if (!online) {
                    // Туннель не поднят — честный отказ, а не чёрная дыра.
                    client.getOutputStream().write("HTTP/1.1 503\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    client.close()
                    return
                }
                streamId = nextStreamId++
                clients[streamId] = client
                publish()
                send(streamId, OP_OPEN, "$host:$port".toByteArray())
                // Оптимистичное 200 — как в Dart-версии: при недоступности цели придёт opError.
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                // Байты, приехавшие вместе с заголовком (пайплайнинг), не теряем.
                if (pipelined != null) send(streamId, OP_DATA, pipelined)
            } else {
                val p = plain!!
                val host = p.groupValues[2]
                val port = p.groupValues[3].toIntOrNull() ?: 80
                val path = p.groupValues[4].ifEmpty { "/" }
                // Цели — origin-form: "GET /path HTTP/1.1" + заголовки без изменений.
                val rewritten = "${p.groupValues[1]} $path HTTP/1.1${request.substring(firstLine.length)}"
                    .toByteArray() + (pipelined ?: ByteArray(0))
                if (isDirectTarget(host)) {
                    relayDirect(client, host, port, clientPrefix = null, upstreamPrefix = rewritten)
                    return
                }
                if (!online) {
                    client.getOutputStream().write("HTTP/1.1 503\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    client.close()
                    return
                }
                streamId = nextStreamId++
                clients[streamId] = client
                publish()
                send(streamId, OP_OPEN, "$host:$port".toByteArray())
                send(streamId, OP_DATA, rewritten)
            }

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

    // ── Прямой обход туннеля для сигналинг-сервера ──────────────────────────

    private fun isDirectTarget(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" ||
            host == "::1" || host == "10.0.2.2"
        ) return true
        val server = prefs.getString("flutter.server", null) ?: return false
        val serverHost = runCatching { java.net.URI(server).host }.getOrNull()
        return serverHost != null && serverHost.equals(host, ignoreCase = true)
    }

    /**
     * Прямое соединение в обход туннеля. [clientPrefix] уходит клиенту сразу после
     * подключения (ответ на CONNECT), [upstreamPrefix] — цели первым делом
     * (пайплайн после CONNECT-заголовка или переписанный plain-HTTP запрос).
     */
    private fun relayDirect(
        client: Socket,
        host: String,
        port: Int,
        clientPrefix: ByteArray?,
        upstreamPrefix: ByteArray?,
    ) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 10_000)
            if (clientPrefix != null) client.getOutputStream().write(clientPrefix)
            if (upstreamPrefix != null) {
                upstream.getOutputStream().write(upstreamPrefix)
                upstream.getOutputStream().flush()
            }
            io.execute {
                runCatching {
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = upstream.getInputStream().read(buf)
                        if (read < 0) break
                        client.getOutputStream().write(buf, 0, read)
                        client.getOutputStream().flush()
                    }
                }
                runCatching { client.close() }
                runCatching { upstream.close() }
            }
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = client.getInputStream().read(buf)
                if (read < 0) break
                upstream.getOutputStream().write(buf, 0, read)
                upstream.getOutputStream().flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "direct relay to $host:$port failed: ${e.message}")
            runCatching {
                client.getOutputStream().write("HTTP/1.1 502\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        } finally {
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
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
