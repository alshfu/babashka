package ru.pult.helper.data

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import ru.pult.core.protocol.IceServer
import ru.pult.core.protocol.PairAuth
import ru.pult.core.webrtc.CONTROL_CHANNEL
import ru.pult.core.webrtc.PeerObserverAdapter
import ru.pult.core.webrtc.TextChannelObserver
import ru.pult.core.webrtc.WebRtcCore
import ru.pult.core.webrtc.createOfferAwait
import ru.pult.core.webrtc.iceCandidateFromJson
import ru.pult.core.webrtc.sendText
import ru.pult.core.webrtc.setLocalDescriptionAwait
import ru.pult.core.webrtc.setRemoteDescriptionAwait
import ru.pult.core.webrtc.toJsonString
import ru.pult.core.webrtc.toWebRtc

/**
 * Приём экрана бабушки поверх libwebrtc.
 *
 * Соединение — строго `recvonly`: помощник ничего не отправляет и отправить не может.
 * Кнопки записи нет и не будет: поток нигде не сохраняется, ни в файл, ни в кеш.
 *
 * Видеотрек может появиться заметно позже согласования — бабушка подставляет его
 * только после проверки подписи. Пустой экран до этого момента — не ошибка, а норма.
 */
class WebRtcViewerTransport(context: Context) : ViewerTransport {

    private val factory = WebRtcCore.factory(context)

    private var peer: PeerConnection? = null
    private var control: DataChannel? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null

    private var onIce: (String) -> Unit = {}
    private var onControl: (String) -> Unit = {}

    override val localFingerprint: String
        get() = PairAuth.fingerprintFromSdp(
            requireNotNull(peer?.localDescription?.description) { "локальный SDP ещё не создан" },
        )

    override fun setIceCandidateListener(listener: (String) -> Unit) {
        onIce = listener
    }

    override fun setControlListener(listener: (String) -> Unit) {
        onControl = listener
    }

    override suspend fun createOffer(iceServers: List<IceServer>): String {
        val config = PeerConnection.RTCConfiguration(iceServers.toWebRtc()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val connection = requireNotNull(factory.createPeerConnection(config, observer)) {
            "не удалось создать PeerConnection"
        }
        peer = connection

        control = connection.createDataChannel(
            CONTROL_CHANNEL,
            DataChannel.Init().apply { ordered = true },
        )?.also { it.registerObserver(TextChannelObserver(onControl)) }

        // Только приём: помощник не отправляет ни видео, ни звук.
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        val offer = connection.createOfferAwait(MediaConstraints())
        connection.setLocalDescriptionAwait(offer)
        return requireNotNull(connection.localDescription).description
    }

    override suspend fun acceptAnswer(sdp: String) {
        val connection = requireNotNull(peer) { "сессия не поднята" }
        connection.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override suspend fun addIceCandidate(candidateJson: String) {
        iceCandidateFromJson(candidateJson)?.let { peer?.addIceCandidate(it) }
    }

    override fun sendControl(payload: String) {
        control?.takeIf { it.state() == DataChannel.State.OPEN }?.sendText(payload)
    }

    /** Куда рисовать экран бабушки. Вызывается с UI-потока экрана сессии. */
    fun attachRenderer(view: SurfaceViewRenderer) {
        view.init(WebRtcCore.eglBase.eglBaseContext, null)
        view.setEnableHardwareScaler(true)
        renderer = view
        remoteTrack?.addSink(view)
    }

    fun detachRenderer() {
        renderer?.let { view ->
            remoteTrack?.removeSink(view)
            view.release()
        }
        renderer = null
    }

    override fun close() {
        detachRenderer()
        remoteTrack = null
        control?.close()
        control = null
        peer?.close()
        peer = null
    }

    private val observer = object : PeerObserverAdapter() {
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
            candidate?.let { onIce(it.toJsonString()) }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            remoteTrack = track
            renderer?.let(track::addSink)
        }
    }
}
