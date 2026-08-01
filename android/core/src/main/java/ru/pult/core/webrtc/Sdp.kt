package ru.pult.core.webrtc

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import ru.pult.core.protocol.IceServer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Тонкая обвязка над колбэчным API libwebrtc: suspend-функции вместо `SdpObserver`,
 * JSON-представление ICE-кандидатов и наблюдатель с пустыми реализациями.
 *
 * Ничего «умного» здесь нет намеренно: вся логика допуска к экрану живёт в
 * `SessionController`/`HelperSession`, а не размазана по обвязке транспорта.
 */

suspend fun PeerConnection.createOfferAwait(
    constraints: MediaConstraints = MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { continuation ->
    createOffer(
        object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) =
                continuation.resume(description)

            override fun onCreateFailure(error: String?) =
                continuation.resumeWithException(IllegalStateException("createOffer: $error"))
        },
        constraints,
    )
}

suspend fun PeerConnection.createAnswerAwait(
    constraints: MediaConstraints = MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { continuation ->
    createAnswer(
        object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) =
                continuation.resume(description)

            override fun onCreateFailure(error: String?) =
                continuation.resumeWithException(IllegalStateException("createAnswer: $error"))
        },
        constraints,
    )
}

suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) =
                    continuation.resumeWithException(IllegalStateException("setLocalDescription: $error"))
            },
            description,
        )
    }

suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) =
                    continuation.resumeWithException(IllegalStateException("setRemoteDescription: $error"))
            },
            description,
        )
    }

abstract class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

/** Наблюдатель соединения с пустыми реализациями: переопределяем только нужное. */
abstract class PeerObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(channel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    override fun onTrack(transceiver: RtpTransceiver?) = Unit
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) = Unit
}

// ── ICE ─────────────────────────────────────────────────────────────────────

private val json = Json { ignoreUnknownKeys = true }

fun IceCandidate.toJsonString(): String = buildJsonObject {
    put("candidate", sdp)
    put("sdpMid", sdpMid)
    put("sdpMLineIndex", sdpMLineIndex)
}.toString()

/** null — кандидат не разобран; такие просто пропускаем, соединение переживёт. */
fun iceCandidateFromJson(text: String): IceCandidate? = runCatching {
    val obj = json.parseToJsonElement(text) as JsonObject
    IceCandidate(
        obj["sdpMid"]?.jsonPrimitive?.content,
        obj["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
        obj["candidate"]!!.jsonPrimitive.content,
    )
}.getOrNull()

/** ICE-серверы из `hello-ok` в вид, понятный libwebrtc. */
fun List<IceServer>.toWebRtc(): List<PeerConnection.IceServer> = map { server ->
    PeerConnection.IceServer.builder(server.urls)
        .setUsername(server.username.orEmpty())
        .setPassword(server.credential.orEmpty())
        .createIceServer()
}

// ── Управляющий канал ───────────────────────────────────────────────────────

const val CONTROL_CHANNEL = "pult-control"

fun DataChannel.sendText(payload: String) {
    send(DataChannel.Buffer(ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8)), false))
}

fun DataChannel.Buffer.asText(): String {
    val bytes = ByteArray(data.remaining())
    data.get(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

/** Наблюдатель канала: интересны только текстовые сообщения. */
class TextChannelObserver(private val onText: (String) -> Unit) : DataChannel.Observer {
    override fun onBufferedAmountChange(previousAmount: Long) = Unit
    override fun onStateChange() = Unit
    override fun onMessage(buffer: DataChannel.Buffer) {
        if (!buffer.binary) onText(buffer.asText())
    }
}
