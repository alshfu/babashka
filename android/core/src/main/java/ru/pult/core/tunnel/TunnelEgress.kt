package ru.pult.core.tunnel

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Исходящая сторона TCP-туннеля на телефоне в Швеции.
 *
 * Держит WebSocket к релею `/tunnel` на сервере; каждый кадр — мультиплексированный
 * кусок TCP-потока от приложения шлюза (Swedbank идёт в сеть с IP этого телефона):
 *   [streamId:uint32 BE][op:uint8][payload]
 *   op: 0=open ("host:port"), 1=data, 2=close, 3=error (только исходящие)
 *
 * Один поток = одно TCP-соединение наружу. Реконнект как у lowlat-стримера:
 * сокет умер → все потоки закрываем (приложение переподключится) → reconnect через 2 с.
 */
class TunnelEgress(private val wsUrl: String) {

    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false

    private class Stream {
        var socket: Socket? = null
        var out: OutputStream? = null
        // Кадры, приехавшие до завершения TCP-connect — иначе ранняя data теряется.
        val pending = mutableListOf<ByteArray>()
        var closed = false
    }

    private val streams = ConcurrentHashMap<Int, Stream>()

    fun start() {
        if (running) return
        running = true
        openSocket()
        Log.i(TAG, "egress started → $wsUrl")
    }

    fun stop() {
        running = false
        runCatching { ws?.close(1000, "stop") }
        ws = null
        closeAllStreams()
    }

    private fun openSocket() {
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { onFrame(bytes.toByteArray()) }
                    .onFailure { Log.w(TAG, "frame error: ${it.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                scheduleReconnect(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect(webSocket)
            }
        })
    }

    private fun scheduleReconnect(dead: WebSocket) {
        synchronized(this) {
            if (!running || ws !== dead) return
            ws = null
        }
        closeAllStreams()
        Thread {
            Thread.sleep(2000)
            synchronized(this) { if (!running || ws != null) return@Thread }
            Log.i(TAG, "ws reconnecting")
            openSocket()
        }.start()
    }

    // ── Кадры ─────────────────────────────────────────────────────────────────

    private fun onFrame(frame: ByteArray) {
        if (frame.size < 5) return
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val streamId = buf.int
        val op = buf.get().toInt()
        val payload = ByteArray(buf.remaining()).also { buf.get(it) }

        when (op) {
            OP_OPEN -> openStream(streamId, String(payload, Charsets.UTF_8))
            OP_DATA -> streams[streamId]?.let { s ->
                synchronized(s) {
                    val o = s.out
                    if (o == null) {
                        // TCP-connect ещё идёт — копим, не теряем.
                        if (!s.closed) s.pending.add(payload)
                    } else {
                        runCatching { o.write(payload); o.flush() }
                            .onFailure { closeStream(streamId, sendClose = true) }
                    }
                }
            }
            OP_CLOSE -> closeStream(streamId, sendClose = false)
        }
    }

    private fun openStream(streamId: Int, target: String) {
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank() || port == null) {
            sendFrame(streamId, OP_ERROR, "bad target".toByteArray())
            return
        }
        // Поток регистрируем СРАЗУ — до коннекта, иначе ранние data-кадры теряются.
        val stream = Stream()
        streams[streamId] = stream
        Thread({
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 10_000)
                socket.tcpNoDelay = true
                val out = socket.getOutputStream()
                synchronized(stream) {
                    if (stream.closed) {
                        runCatching { socket.close() }
                        return@Thread
                    }
                    stream.socket = socket
                    stream.out = out
                    stream.pending.forEach { out.write(it) }
                    stream.pending.clear()
                    out.flush()
                }
                // Читалка: ответы хоста → data-кадры в приложение.
                Thread({
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    try {
                        while (true) {
                            val n = socket.getInputStream().read(buf)
                            if (n < 0) break
                            total += n
                            sendFrame(streamId, OP_DATA, buf.copyOf(n))
                        }
                    } catch (_: Exception) {
                        // сокет умер — закрываем поток ниже
                    }
                    Log.i(TAG, "stream $streamId closed after ${total}B rx")
                    closeStream(streamId, sendClose = true)
                }, "tunnel-rx-$streamId").apply { isDaemon = true; start() }
                Log.i(TAG, "stream $streamId → $target open")
            } catch (e: Exception) {
                Log.w(TAG, "stream $streamId → $target failed: ${e.message}")
                closeStream(streamId, sendClose = false)
                sendFrame(streamId, OP_ERROR, (e.message ?: "connect failed").toByteArray())
            }
        }, "tunnel-open-$streamId").apply { isDaemon = true; start() }
    }

    private fun closeStream(streamId: Int, sendClose: Boolean) {
        val s = streams.remove(streamId) ?: return
        synchronized(s) {
            s.closed = true
            s.pending.clear()
            runCatching { s.socket?.close() }
        }
        if (sendClose) sendFrame(streamId, OP_CLOSE, ByteArray(0))
    }

    private fun closeAllStreams() {
        for (id in streams.keys.toList()) closeStream(id, sendClose = false)
    }

    private fun sendFrame(streamId: Int, op: Int, payload: ByteArray) {
        val socket = ws ?: return
        val buf = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(streamId)
        buf.put(op.toByte())
        buf.put(payload)
        socket.send(ByteString.of(*buf.array()))
    }

    private companion object {
        const val TAG = "PultTunnel"
        const val OP_OPEN = 0
        const val OP_DATA = 1
        const val OP_CLOSE = 2
        const val OP_ERROR = 3
    }
}
