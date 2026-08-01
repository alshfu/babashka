package ru.pult.grandma.session

import android.content.Intent
import ru.pult.core.protocol.IceServer

/**
 * Транспорт-заглушка для тестов и для сборок без libwebrtc.
 *
 * Намеренно не «делает вид, что работает»: попытка поднять сессию упирается в явную
 * ошибку. Заглушка, отдающая пустой SDP, была бы хуже — она ломала бы проверку MAC
 * и выглядела как проблема в криптографии.
 */
class PendingScreenTransport : ScreenTransport {

    override val localFingerprint: String
        get() = notReady()

    override suspend fun answer(offerSdp: String, iceServers: List<IceServer>): String = notReady()

    override suspend fun addIceCandidate(candidateJson: String) = notReady()

    override suspend fun startScreen(screenCapturePermission: Intent?) = notReady()

    override fun hasCapture(): Boolean = false

    override fun releaseCapture() = Unit

    override suspend fun setRedacted(on: Boolean, reason: String) = notReady()

    override fun sendControl(payload: String) = notReady()

    override fun setIceCandidateListener(listener: (String) -> Unit) = Unit

    override fun setControlListener(listener: (String) -> Unit) = Unit

    override fun setScreenStoppedListener(listener: () -> Unit) = Unit

    override fun close() = Unit

    private fun notReady(): Nothing =
        error("WebRTC-транспорт отключён в этой сборке: используйте WebRtcScreenTransport")
}
