package ru.pult.core.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Общая фабрика WebRTC на процесс.
 *
 * `PeerConnectionFactory` тяжёлая (нативная библиотека, свои потоки), и создавать её
 * на каждую сессию нельзя: бабушка зовёт помощь редко, но телефон у неё слабый.
 * `EglBase` тоже один: его контекст нужен и энкодеру на стороне бабушки,
 * и рендереру на стороне внука.
 */
object WebRtcCore {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Volatile
    private var egl: EglBase? = null

    val eglBase: EglBase
        get() = egl ?: synchronized(this) {
            egl ?: EglBase.create().also { egl = it }
        }

    fun factory(context: Context): PeerConnectionFactory =
        factory ?: synchronized(this) {
            factory ?: build(context.applicationContext).also { factory = it }
        }

    private fun build(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                // Трассировку и внутренний логгер не включаем: в них попадают SDP и адреса.
                .createInitializationOptions(),
        )

        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true,
                ),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Параметры потока: читаемость экрана важнее плавности, трафик у бабушки часто лимитный. */
    object Video {
        const val MAX_LONG_SIDE = 1280
        // 24 к/с: на этапе разработки и интерактивного управления плавность важнее
        // экономии трафика. В продакшене можно снизить до 10–15 к/с для слабых устройств.
        const val FPS = 24

        /** Ужимаем реальное разрешение экрана до 720p по длинной стороне, сохраняя пропорции. */
        fun scale(width: Int, height: Int): Pair<Int, Int> {
            val longSide = maxOf(width, height)
            if (longSide <= MAX_LONG_SIDE) return width to height
            val factor = MAX_LONG_SIDE.toDouble() / longSide
            // Чётные размеры: нечётные ломают часть аппаратных энкодеров.
            fun even(value: Double) = (value.toInt() / 2) * 2
            return even(width * factor) to even(height * factor)
        }
    }
}
