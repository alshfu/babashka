package ru.pult.helper.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.pult.core.net.SignalingClient
import ru.pult.core.pairing.PairRecord
import ru.pult.core.protocol.EndReason
import ru.pult.core.protocol.IceServer
import ru.pult.core.protocol.PairAuth
import ru.pult.core.protocol.Role
import ru.pult.core.protocol.Signal
import ru.pult.core.session.SessionMachine

/**
 * Сторона помощника: запрос помощи, проверка подписи бабушки, показ её экрана.
 *
 * Важное отличие от стороны бабушки: помощник **не** решает, будет ли показ. Он может
 * только попросить. Всё, что здесь есть, — вежливый вопрос и корректное поведение
 * при отказе.
 */
class HelperSession(
    private val pair: PairRecord,
    private val signaling: SignalingClient,
    private val transport: ViewerTransport,
    private val scope: CoroutineScope,
) {

    sealed interface Ui {
        data object Idle : Ui
        data class Waiting(val peerOnline: Boolean) : Ui
        data object Connecting : Ui
        data class Watching(val redacted: Boolean) : Ui
        data class Refused(val reason: String) : Ui
    }

    private val machine = SessionMachine(Role.HELPER)
    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var iceServers: List<IceServer> = emptyList()
    private var nonceH: String? = null
    private var nonceG: String? = null

    init {
        transport.setIceCandidateListener { candidateJson ->
            val sessionId = machine.sessionId ?: return@setIceCandidateListener
            signaling.send(Signal.Ice(sessionId, Json.parseToJsonElement(candidateJson)))
        }
        transport.setControlListener(::onControl)
    }

    /** Сообщения от бабушки. Ничего из этого помощник не может отменить — только увидеть. */
    private fun onControl(payload: String) {
        val message = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        when (message["t"]?.jsonPrimitive?.content) {
            "redact" -> onRedact(message["on"]?.jsonPrimitive?.booleanOrNull ?: false)
            "stop" -> reset(Ui.Refused(EndReason.STOPPED_BY_GRANDMA))
        }
    }

    /** Единственное, что помощник может инициировать. Дальше решает бабушка. */
    fun requestHelp(note: String?) {
        if (machine.state != SessionMachine.State.IDLE) return
        signaling.send(Signal.HelpRequest(note = note?.takeIf(String::isNotBlank)))
    }

    fun cancel() {
        val sessionId = machine.sessionId ?: return
        signaling.send(Signal.SessionEnd(sessionId, EndReason.CANCELLED))
        reset(Ui.Idle)
    }

    fun finish() {
        val sessionId = machine.sessionId ?: return
        signaling.send(Signal.SessionEnd(sessionId, EndReason.STOPPED_BY_HELPER))
        reset(Ui.Idle)
    }

    fun pointer(x: Float, y: Float, kind: String = "tap") {
        transport.sendControl("""{"t":"pointer","x":$x,"y":$y,"kind":"$kind"}""")
    }

    fun say(text: String) {
        transport.sendControl("""{"t":"say","text":"${text.take(60).replace("\"", "")}"}""")
    }

    fun handle(signal: Signal) {
        when (signal) {
            is Signal.HelloOk -> iceServers = signal.iceServers

            is Signal.HelpRequestSent -> {
                machine.onHelpRequest(signal.sessionId)
                _ui.value = Ui.Waiting(signal.peerOnline)
            }

            is Signal.ConsentGranted -> {
                if (!machine.belongsToCurrent(signal.sessionId)) return
                machine.onConsent()
                _ui.value = Ui.Connecting
                scope.launch { sendOffer(signal.sessionId) }
            }

            is Signal.ConsentDenied -> {
                if (!machine.belongsToCurrent(signal.sessionId)) return
                // Отказ — нормальный исход. Никаких «попробовать ещё раз» крупной кнопкой.
                reset(Ui.Refused(EndReason.DECLINED))
            }

            is Signal.Answer -> scope.launch { onAnswer(signal) }

            is Signal.Ice -> scope.launch {
                if (machine.belongsToCurrent(signal.sessionId)) {
                    signal.candidate?.let { transport.addIceCandidate(it.toString()) }
                }
            }

            is Signal.SessionEnd -> {
                if (machine.belongsToCurrent(signal.sessionId)) reset(Ui.Refused(signal.reason))
            }

            else -> Unit
        }
    }

    private suspend fun sendOffer(sessionId: String) {
        nonceH = PairAuth.newNonce()
        val sdp = transport.createOffer(iceServers)
        signaling.send(Signal.Offer(sessionId, sdp, nonceH!!))
    }

    private suspend fun onAnswer(signal: Signal.Answer) {
        if (!machine.belongsToCurrent(signal.sessionId)) return
        nonceG = signal.nonceG

        // Отпечаток берём из ПРИШЕДШЕГО SDP: подменил сигналинг — MAC не сойдётся.
        val fpG = PairAuth.fingerprintFromSdp(signal.sdp)
        val transcript = PairAuth.transcript(
            sessionId = signal.sessionId,
            nonceH = nonceH.orEmpty(),
            nonceG = signal.nonceG,
            fpH = transport.localFingerprint,
            fpG = fpG,
        )

        if (!PairAuth.verify(PairAuth.mac(pair.secret, Role.GRANDMA, transcript), signal.macG)) {
            signaling.send(Signal.SessionEnd(signal.sessionId, EndReason.AUTH_FAILED))
            reset(Ui.Refused(EndReason.AUTH_FAILED))
            return
        }

        transport.acceptAnswer(signal.sdp)
        signaling.send(
            Signal.AuthConfirm(signal.sessionId, PairAuth.mac(pair.secret, Role.HELPER, transcript)),
        )
        machine.onPeerAuthenticated()
        _ui.value = Ui.Watching(redacted = false)
    }

    /** Гашение приходит от бабушки; отменить его помощник не может — только увидеть. */
    fun onRedact(on: Boolean) {
        if (machine.state == SessionMachine.State.CONNECTED) _ui.value = Ui.Watching(on)
    }

    private fun reset(ui: Ui) {
        transport.close()
        machine.onEnd()
        nonceH = null
        nonceG = null
        _ui.value = ui
    }
}
