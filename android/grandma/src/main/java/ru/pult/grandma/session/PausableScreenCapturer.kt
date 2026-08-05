package ru.pult.grandma.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink

/**
 * Капчурер экрана с паузой без повторного системного диалога MediaProjection.
 *
 * libwebrtc ScreenCapturerAndroid при stopCapture() рвёт и проекцию: повторный запуск
 * требует того же системного диалога, а на MIUI он из фона недоступен. Здесь проекция
 * берётся из разрешения один раз и живёт до dispose():
 *
 *  - пауза (банковское приложение на переднем плане) — отпустить VirtualDisplay:
 *    захват физически прекращается, BankID перестаёт видеть запись экрана и принимает
 *    нажатия, а проекция остаётся живой;
 *  - возобновление — создать новый VirtualDisplay из той же проекции: диалог не нужен.
 *
 * Конвейер кадров повторяет libwebrtc: VirtualDisplay пишет в SurfaceTexture из
 * SurfaceTextureHelper, кадры уходят в VideoSource через CapturerObserver.
 *
 * Все публичные методы — под внутренним локом: setRedacted, close и releaseCapture
 * приходят с разных потоков (корутины сессии, системные колбэки, остановка сервиса).
 */
class PausableScreenCapturer(
    private val permission: Intent,
    private val onStopped: () -> Unit,
) : VideoCapturer {

    private var appContext: Context? = null
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    private var width = 0
    private var height = 0
    private var fps = 0

    private var disposed = false
    private var capturing = false

    /** Системная остановка проекции (бабушка нажала «Стоп» в системной панели). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "проекция остановлена системой")
            synchronized(this@PausableScreenCapturer) {
                releaseDisplayLocked()
                mediaProjection = null
                capturing = false
            }
            // Слушателя зовём вне лока: для сессии это обычный «Стоп», а он трогает транспорт.
            onStopped()
        }
    }

    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        synchronized(this) {
            appContext = context.applicationContext
            this.helper = helper
            this.observer = observer
        }
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, fps: Int) {
        check(!disposed) { "капчурер уже освобождён" }
        if (capturing) {
            Log.w(TAG, "startCapture при уже идущем захвате — игнорируем")
            return
        }
        this.width = width
        this.height = height
        this.fps = fps

        ensureProjectionLocked()
        startDisplayLocked()
        observer?.onCapturerStarted(true)
        // Слушатель кадров вешается один раз: пауза/возобновление его не трогают —
        // без VirtualDisplay кадры просто не приходят. Ссылку фиксируем локально,
        // чтобы доставка кадров не читала поле без лока на потоке хелпера.
        val frameObserver = observer
        helper?.startListening(VideoSink { frame -> frameObserver?.onFrameCaptured(frame) })
        Log.i(TAG, "захват запущен: ${width}x$height@$fps")
    }

    /** Полная остановка захвата (releaseCapture). Проекцию не рвём — это делает dispose(). */
    @Synchronized
    override fun stopCapture() {
        if (disposed) return
        Log.i(TAG, "остановка захвата")
        releaseDisplayLocked()
        capturing = false
        runCatching { helper?.stopListening() }
        observer?.onCapturerStopped()
    }

    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        this.width = width
        this.height = height
        this.fps = fps
        if (!capturing) return
        Log.i(TAG, "смена формата: ${width}x$height@$fps — пересоздаём VirtualDisplay")
        runCatching { startDisplayLocked() }
            .onFailure { Log.e(TAG, "не удалось пересоздать VirtualDisplay", it) }
    }

    @Synchronized
    override fun dispose() {
        if (disposed) return
        disposed = true
        Log.i(TAG, "освобождение капчурера и проекции")
        releaseDisplayLocked()
        capturing = false
        // Отписываемся ДО stop(): остановка здесь наша инициатива, onStopped не нужен.
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
            .onFailure { Log.w(TAG, "ошибка при остановке проекции", it) }
        mediaProjection = null
        helper = null
        observer = null
        appContext = null
    }

    override fun isScreencast(): Boolean = true

    /**
     * Пауза захвата: отпустить VirtualDisplay и Surface, проекцию и слушателя кадров
     * не трогать. Банковское приложение перестаёт видеть активный захват экрана.
     */
    @Synchronized
    fun pauseCapture() {
        if (!capturing) return
        releaseDisplayLocked()
        capturing = false
        Log.i(TAG, "пауза захвата: VirtualDisplay отпущен, проекция жива")
    }

    /**
     * Возобновление захвата: новый VirtualDisplay из той же проекции — системный
     * диалог не нужен. false, если проекции уже нет (её остановила система).
     */
    @Synchronized
    fun resumeCapture(): Boolean {
        if (disposed || capturing) return capturing
        if (mediaProjection == null) {
            Log.e(TAG, "возобновить нельзя: проекции нет (система уже остановила?)")
            return false
        }
        return try {
            startDisplayLocked()
            Log.i(TAG, "захват возобновлён из той же проекции")
            true
        } catch (e: Exception) {
            Log.e(TAG, "не удалось возобновить захват из сохранённой проекции", e)
            false
        }
    }

    /** Проекция берётся ровно один раз: токен согласия одноразовый, повторный
     *  getMediaProjection по тому же Intent на Android 14+ падает. */
    private fun ensureProjectionLocked() {
        if (mediaProjection != null) return
        val context = appContext ?: error("капчурер не инициализирован")
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjectionManager недоступен")
        val projection = manager.getMediaProjection(Activity.RESULT_OK, permission)
            ?: error("система не выдала MediaProjection из разрешения")
        // Callback регистрируем ДО создания VirtualDisplay — иначе на Android 14+
        // createVirtualDisplay падает с IllegalStateException.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection
        Log.i(TAG, "проекция получена из разрешения")
    }

    /** Создать VirtualDisplay из проекции. Как в libwebrtc: размер текстуры → Surface → дисплей. */
    private fun startDisplayLocked() {
        val projection = mediaProjection ?: error("проекции нет")
        val textureHelper = helper ?: error("капчурер не инициализирован")

        releaseDisplayLocked() // при пересоздании старого не должно остаться
        textureHelper.setTextureSize(width, height)
        val newSurface = Surface(textureHelper.surfaceTexture)
        virtualDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME, width, height, VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS, newSurface, null, null,
        )
        surface = newSurface
        capturing = true
    }

    /** Отпустить VirtualDisplay и Surface. Проекция при этом остаётся живой. */
    private fun releaseDisplayLocked() {
        runCatching { virtualDisplay?.release() }
            .onFailure { Log.w(TAG, "ошибка при освобождении VirtualDisplay", it) }
        virtualDisplay = null
        runCatching { surface?.release() }
            .onFailure { Log.w(TAG, "ошибка при освобождении Surface", it) }
        surface = null
    }

    private companion object {
        const val TAG = "PultCapture"

        // Те же параметры виртуального дисплея, что у libwebrtc ScreenCapturerAndroid.
        const val DISPLAY_NAME = "WebRTC_ScreenCapture"
        const val VIRTUAL_DISPLAY_DPI = 400
        const val DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }
}
