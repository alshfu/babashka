package ru.pult.grandma.session

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import ru.pult.core.protocol.IceServer
import ru.pult.core.protocol.PairAuth
import ru.pult.core.webrtc.CONTROL_CHANNEL
import ru.pult.core.webrtc.PeerObserverAdapter
import ru.pult.core.webrtc.TextChannelObserver
import ru.pult.core.webrtc.WebRtcCore
import ru.pult.core.webrtc.createAnswerAwait
import ru.pult.core.webrtc.iceCandidateFromJson
import ru.pult.core.webrtc.sendText
import ru.pult.core.webrtc.setLocalDescriptionAwait
import ru.pult.core.webrtc.setRemoteDescriptionAwait
import ru.pult.core.webrtc.toJsonString
import ru.pult.core.webrtc.toWebRtc

/**
 * Показ экрана бабушки поверх libwebrtc.
 *
 * Порядок здесь — не деталь реализации, а суть безопасности продукта:
 *
 *  1. `answer()` создаёт соединение и отвечает на offer, переводя видеотрансивер
 *     в `SEND_ONLY` **без трека**. Согласование проходит, кадров нет.
 *  2. `startScreen()` вызывается только после проверки MAC и подставляет реальный трек
 *     через `sender.setTrack(...)` — повторного согласования не требуется.
 *
 * Отсюда следует: даже если помощник поднимет соединение, до проверки пары он получит
 * ровно ничего — не «чёрный экран», а отсутствие медиа вообще.
 *
 * Кадры нигде не сохраняются: захват идёт напрямую в энкодер.
 *
 * Захват умеет вставать на паузу и сниматься с неё без системного диалога
 * (см. [PausableScreenCapturer]): на банковских приложениях отпускается VirtualDisplay,
 * сама проекция остаётся живой — показ возобновляется из той же проекции.
 */
class WebRtcScreenTransport(private val context: Context) : ScreenTransport {

    private val factory = WebRtcCore.factory(context)

    private var peer: PeerConnection? = null
    private var videoTransceiver: RtpTransceiver? = null
    private var capturer: PausableScreenCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var control: DataChannel? = null

    private var onIce: (String) -> Unit = {}
    private var onControl: (String) -> Unit = {}
    private var onScreenStopped: () -> Unit = {}

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

    override fun setScreenStoppedListener(listener: () -> Unit) {
        onScreenStopped = listener
    }

    override suspend fun answer(offerSdp: String, iceServers: List<IceServer>): String {
        val config = PeerConnection.RTCConfiguration(iceServers.toWebRtc()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Пробуем оба пути: прямой и через TURN. Что сработает — решит ICE.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val connection = requireNotNull(factory.createPeerConnection(config, observer)) {
            "не удалось создать PeerConnection"
        }
        peer = connection

        connection.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.OFFER, offerSdp))

        // Трансивер отвечает «я буду отправлять», но трека в нём пока нет — захват не идёт.
        videoTransceiver = connection.transceivers.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }?.apply { direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY }

        val answer = connection.createAnswerAwait(MediaConstraints())
        connection.setLocalDescriptionAwait(answer)
        return requireNotNull(connection.localDescription).description
    }

    override suspend fun addIceCandidate(candidateJson: String) {
        iceCandidateFromJson(candidateJson)?.let { peer?.addIceCandidate(it) }
    }

    /** Единственное место в приложении, где вообще начинается захват экрана. */
    override suspend fun startScreen(screenCapturePermission: Intent?) {
        // Трансивер уже согласован в `answer()` — подстановка трека не требует renegotiation.
        val sender = requireNotNull(videoTransceiver?.sender) { "сессия не поднята" }

        // Проекция берётся один раз и живёт между сессиями: на MIUI повторный системный
        // диалог из фона недоступен. Если конвейер уже поднят — просто переиспользуем трек.
        val (width, height) = ensureCapture(screenCapturePermission)
        sender.setTrack(videoTrack, /* takeOwnership = */ false)

        sendControl("""{"t":"screen-state","on":true,"w":$width,"h":$height}""")
    }

    /** Поднять конвейер захвата при первой сессии; на последующих — вернуть текущий размер. */
    private fun ensureCapture(permission: Intent?): Pair<Int, Int> {
        val (width, height) = captureSize()
        if (videoTrack != null) return width to height

        val perm = requireNotNull(permission) { "нет разрешения на захват, а проекции ещё нет" }
        // Свой капчурер: проекция живёт до releaseCapture, а VirtualDisplay можно
        // отпускать (пауза на банковских приложениях) и поднимать заново без диалога.
        val screenCapturer = PausableScreenCapturer(perm) {
            // Бабушка остановила показ системными средствами — это тоже «Стоп».
            onScreenStopped()
        }
        capturer = screenCapturer

        val helper = SurfaceTextureHelper.create("pult-capture", WebRtcCore.eglBase.eglBaseContext)
        surfaceHelper = helper

        val source = factory.createVideoSource(/* isScreencast = */ true)
        videoSource = source
        screenCapturer.initialize(helper, context, source.capturerObserver)
        screenCapturer.startCapture(width, height, WebRtcCore.Video.FPS)

        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        return width to height
    }

    override fun hasCapture(): Boolean = videoTrack != null

    override suspend fun setRedacted(on: Boolean, reason: String, packageName: String?) {
        if (on) {
            // Гашение: сначала пауза самого захвата — VirtualDisplay отпускается, проекция
            // остаётся живой. Банковское приложение перестаёт видеть запись экрана и снова
            // принимает нажатия. Затем снимаем трек: помощник видит пустоту, а не «последний кадр».
            capturer?.pauseCapture()
            videoTransceiver?.sender?.setTrack(null, false)
        } else {
            // Снятие гашения: новый VirtualDisplay из той же проекции — системный диалог
            // не нужен. Затем возвращаем трек отправителю.
            if (capturer?.resumeCapture() != true) {
                Log.w(TAG, "захват не возобновлён: проекция недоступна")
            }
            videoTransceiver?.sender?.setTrack(videoTrack, false)
        }
        val pkgJson = packageName?.let { ",\"pkg\":\"$it\"" } ?: ""
        sendControl("""{"t":"redact","on":$on,"reason":"$reason"$pkgJson}""")
    }

    override fun sendControl(payload: String) {
        control?.takeIf { it.state() == DataChannel.State.OPEN }?.sendText(payload)
    }

    /**
     * Завершение ОДНОЙ сессии: снимаем трек с отправителя и освобождаем соединение.
     * Конвейер захвата (капчурер, источник, трек, проекция) НЕ трогаем — он живёт между
     * сессиями, чтобы не запрашивать системный диалог заново (на MIUI это из фона нельзя).
     * Порядок важен: сначала снять трек, затем dispose соединения — иначе SIGABRT на
     * signaling_thread (проверено на MIUI). Каждый шаг под runCatching.
     */
    override fun close() {
        runCatching { videoTransceiver?.sender?.setTrack(null, false) }
        runCatching { control?.dispose() }
        runCatching { peer?.dispose() }

        control = null
        videoTransceiver = null
        peer = null
    }

    /**
     * Полное освобождение захвата и проекции — при остановке сервиса, не сессии.
     * Каноничный порядок AppRTCMobile: остановить капчурер → dispose источника и трека →
     * и только В КОНЦЕ SurfaceTextureHelper (после того, как кадры перестали в него идти).
     */
    override fun releaseCapture() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { surfaceHelper?.dispose() }

        capturer = null
        videoTrack = null
        videoSource = null
        surfaceHelper = null
    }

    private val observer = object : PeerObserverAdapter() {
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
            candidate?.let { onIce(it.toJsonString()) }
        }

        override fun onDataChannel(channel: DataChannel?) {
            // Канал создаёт помощник; мы его только принимаем.
            if (channel?.label() != CONTROL_CHANNEL) return
            control = channel
            channel.registerObserver(TextChannelObserver(onControl))
        }
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return WebRtcCore.Video.scale(metrics.widthPixels, metrics.heightPixels)
    }

    private companion object {
        const val TAG = "PultCapture"
        const val VIDEO_TRACK_ID = "pult-screen"
    }
}
