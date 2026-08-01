package ru.pult.helper.data

import ru.pult.core.protocol.IceServer

/**
 * Приём экрана по WebRTC (сторона помощника).
 *
 * Вынесено за интерфейс по той же причине, что и у бабушки: правила сессии
 * (проверка подписи до показа, корректное поведение при отказе) должны тестироваться
 * без libwebrtc и без двух телефонов.
 */
interface ViewerTransport {

    val localFingerprint: String

    suspend fun createOffer(iceServers: List<IceServer>): String

    suspend fun acceptAnswer(sdp: String)

    suspend fun addIceCandidate(candidateJson: String)

    fun sendControl(payload: String)

    fun setIceCandidateListener(listener: (String) -> Unit)

    fun setControlListener(listener: (String) -> Unit)

    fun close()
}

/** Заглушка для тестов и сборок без libwebrtc: честно падает, а не имитирует показ. */
class PendingViewerTransport : ViewerTransport {

    override val localFingerprint: String get() = notReady()

    override suspend fun createOffer(iceServers: List<IceServer>): String = notReady()

    override suspend fun acceptAnswer(sdp: String) = notReady()

    override suspend fun addIceCandidate(candidateJson: String) = notReady()

    override fun sendControl(payload: String) = Unit

    override fun setIceCandidateListener(listener: (String) -> Unit) = Unit

    override fun setControlListener(listener: (String) -> Unit) = Unit

    override fun close() = Unit

    private fun notReady(): Nothing =
        error("WebRTC-транспорт отключён в этой сборке: используйте WebRtcViewerTransport")
}
