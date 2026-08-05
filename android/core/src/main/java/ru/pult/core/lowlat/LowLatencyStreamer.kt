package ru.pult.core.lowlat

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer

/**
 * Прототип низколатентной трансляции экрана (scrcpy-подход).
 *
 * MediaProjection → VirtualDisplay → входной Surface энкодера H.264 (MediaCodec) → байты
 * annex-b прямо по WebSocket на релей `/lowlat`. Без jitter buffer и congestion control
 * WebRTC — ради минимальной задержки под управление. Браузер декодирует через WebCodecs.
 *
 * Класс ничего не знает про accessibility: входящие текст-команды (тап/свайп/навигация)
 * отдаём наружу через [onCommand], а дёргает службу управления уже модуль бабушки.
 *
 * Формат кадра (бинарное сообщение): [type:1][ptsMicros:8 BE][annex-b].
 *   type 0 = codec config, 1 = keyframe (SPS+PPS+IDR), 2 = delta.
 */
class LowLatencyStreamer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val wsUrl: String,
    private val onCommand: (String) -> Unit,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val fps: Int = DEFAULT_FPS,
) {

    private val client = OkHttpClient.Builder().pingInterval(java.time.Duration.ofSeconds(15)).build()
    private var ws: WebSocket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    /** Кэш SPS/PPS (codec config) — дописываем к каждому keyframe, чтобы поздний зритель декодировал. */
    private var csd: ByteArray? = null

    // Состояние дропа кадров при переполнении очереди сокета (см. drainLoop).
    @Volatile private var dropping = false
    private var dropped = 0

    fun start() {
        if (running) return
        running = true
        openSocket()
        startEncoder()
        Log.i(TAG, "streamer started ${width}x$height → $wsUrl")
    }

    fun stop() {
        running = false
        runCatching { virtualDisplay?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { ws?.close(1000, "stop") }
        runCatching { projection.stop() }
        virtualDisplay = null; encoder = null; inputSurface = null; ws = null
        Log.i(TAG, "streamer stopped")
    }

    private fun openSocket() {
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Команды панели (тап/свайп/навигация) — наружу, в службу управления.
                runCatching { onCommand(text) }
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

    // Сокет умер (рестарт релея, мигание сети) — переподключаемся сами, иначе
    // трансляция молча мертвеет до перезапуска службы. Энкодер при этом живёт
    // дальше, кадры просто некуда слать — после reconnect всё продолжится.
    private fun scheduleReconnect(dead: WebSocket) {
        synchronized(this) {
            if (!running || ws !== dead) return
            ws = null
        }
        Thread {
            Thread.sleep(2000)
            synchronized(this) { if (!running || ws != null) return@Thread }
            Log.i(TAG, "ws reconnecting")
            openSocket()
        }.start()
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Потолок битрейта — важен и для трафика, и для задержки (нет bufferbloat).
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // keyframe раз в секунду — поздний зритель ждёт ≤1с
            // Baseline без B-кадров = меньше задержки.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)   // low-latency режим энкодера
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // realtime
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = projection.createVirtualDisplay(
            "pult-lowlat",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )

        drainThread = Thread({ drainLoop() }, "pult-lowlat-drain").also { it.start() }
    }

    private fun drainLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buffer: ByteBuffer? = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)

                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (isConfig) {
                    csd = bytes
                    sendFrame(TYPE_CONFIG, info.presentationTimeUs, bytes)
                } else {
                    // Backpressure: если сокет не успевает отгружать (канал уже битрейта),
                    // дельта-кадры выбрасываем до ближайшего keyframe, а не копим очередь
                    // OkHttp — иначе зритель смотрит «прошлое» с нарастающим отставанием.
                    val queued = ws?.queueSize() ?: 0
                    val drop = when {
                        dropping && !isKey -> true
                        !isKey && queued > MAX_QUEUE_BYTES -> {
                            Log.w(TAG, "ws queue ${queued}B — drop to keyframe (dropped=$dropped)")
                            true
                        }
                        else -> false
                    }
                    if (drop) {
                        dropping = true
                        dropped++
                    } else {
                        dropping = false
                        // Keyframe отправляем вместе с SPS/PPS — иначе поздний зритель не декодирует.
                        val payload = if (isKey && csd != null) csd!! + bytes else bytes
                        sendFrame(if (isKey) TYPE_KEY else TYPE_DELTA, info.presentationTimeUs, payload)
                    }
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun sendFrame(type: Int, ptsUs: Long, payload: ByteArray) {
        val socket = ws ?: return
        val header = ByteArray(9)
        header[0] = type.toByte()
        var p = ptsUs
        for (k in 8 downTo 1) { header[k] = (p and 0xff).toByte(); p = p shr 8 }
        val msg: ByteString = ByteString.of(*(header + payload))
        socket.send(msg)
    }

    companion object {
        private const val TAG = "PultLowLat"
        private const val TYPE_CONFIG = 0
        private const val TYPE_KEY = 1
        private const val TYPE_DELTA = 2
        // ~1.3 с потока 3 Мбит/с: больше копить нельзя — это уже не «live».
        private const val MAX_QUEUE_BYTES = 512 * 1024L
        const val DEFAULT_BITRATE = 3_000_000
        const val DEFAULT_FPS = 30
    }
}
