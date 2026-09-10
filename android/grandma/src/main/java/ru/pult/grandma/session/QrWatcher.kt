package ru.pult.grandma.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.VideoFrame
import ru.pult.grandma.control.BankIdMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR-наблюдение за экраном BankID во время удалённого входа.
 *
 * BankID при входе «с другого устройства» показывает анимированный QR, который
 * должен отсканировать помощник. Кадры идут из WebRTC-захвата (см. PausableScreenCapturer),
 * декодируется только Y-плоскость, не чаще раза в 700 мс и только когда выполнено всё:
 * идёт сессия, на переднем плане BankID, режим REMOTE. Найденный код уходит помощнику
 * по control-каналу и пишется в локальный журнал. Защищённый экран (FLAG_SECURE) даёт
 * чёрные кадры — декод просто ничего не находит, это не ошибка.
 */
class QrWatcher(
    private val context: Context,
    private val send: (String) -> Unit,
) {

    @Volatile
    private var running = false

    @Volatile
    private var bankIdVisible = false

    private var lastSampleAt = 0L
    private var lastPayload: String? = null
    private var lastPayloadAt = 0L
    private val decoding = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-watcher").apply { isDaemon = true }
    }

    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun start() {
        running = true
    }

    fun stop() {
        running = false
        bankIdVisible = false
        lastPayload = null
    }

    /** Сервис сообщает текущее foreground-приложение (из ForegroundAppWatcher). */
    fun onForegroundPackage(packageName: String?) {
        bankIdVisible = packageName == ForegroundAppWatcher.BANKID_PACKAGE
    }

    /**
     * Кадр из капчурера — приходит на потоке SurfaceTextureHelper и жив только на время
     * вызова. Здесь только условия, троттлинг и копия Y-плоскости; декод — в фоне,
     * чтобы не задерживать подачу кадров в энкодер.
     */
    fun onFrame(frame: VideoFrame) {
        if (!running || !bankIdVisible || !BankIdMode.isRemote()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        if (decoding.get()) return
        lastSampleAt = now

        val i420 = runCatching { frame.buffer.toI420() }.getOrNull() ?: return
        val width = i420.width
        val height = i420.height
        val y = ByteArray(width * height)
        val src = i420.dataY
        val stride = i420.strideY
        if (stride == width) {
            src.get(y)
        } else {
            val row = ByteArray(width)
            for (r in 0 until height) {
                src.position(r * stride)
                src.get(row)
                System.arraycopy(row, 0, y, r * width, width)
            }
        }
        i420.release()

        decoding.set(true)
        executor.execute { decode(y, width, height) }
    }

    private fun decode(y: ByteArray, width: Int, height: Int) {
        try {
            val reader = MultiFormatReader()
            val text = try {
                val source = PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
            } catch (_: Exception) {
                null // QR на кадре нет (или экран защищён) — обычное состояние
            } finally {
                reader.reset()
            } ?: return

            val at = System.currentTimeMillis()
            if (text == lastPayload && at - lastPayloadAt < DEDUP_MS) return
            lastPayload = text
            lastPayloadAt = at
            Log.i(TAG, "QR на экране BankID: ${text.take(60)}")
            send(
                buildJsonObject {
                    put("t", "qr")
                    put("payload", text)
                    put("at", at)
                }.toString(),
            )
            runCatching {
                File(context.filesDir, LOG_FILE).appendText("$at,${text.replace('\n', ' ')}\n")
            }
        } finally {
            decoding.set(false)
        }
    }

    private companion object {
        const val TAG = "PultQr"
        const val LOG_FILE = "bankid-qr.log"
        const val SAMPLE_INTERVAL_MS = 700L
        const val DEDUP_MS = 10_000L
    }
}
