package ru.pult.grandma.session

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.pult.core.net.SignalingClient
import ru.pult.core.pairing.PairRecord
import ru.pult.core.protocol.EndReason
import ru.pult.core.protocol.IceServer
import ru.pult.core.protocol.PairAuth
import ru.pult.core.protocol.Role
import ru.pult.core.protocol.Signal
import ru.pult.core.session.SessionMachine

/**
 * Единственная точка входа в сессию на устройстве бабушки.
 *
 * Правила, ради которых этот класс существует (docs/security.md §6):
 *  1. Запрос помощи сам по себе не даёт ничего — только показывает вопрос.
 *  2. `offer` без предшествующего согласия остаётся без ответа.
 *  3. Захват экрана запускается ТОЛЬКО после проверки MAC помощника, локально.
 *  4. Любое завершение — включая отказ и молчание — попадает в журнал.
 *
 * Захват стартует ровно в одном месте (`onAuthConfirm`), и обойти его нельзя:
 * `MediaProjection` в контроллер попадает отдельно, а `machine.canCaptureScreen`
 * истинно лишь в состоянии CONNECTED.
 */
class SessionController(
    private val pair: PairRecord,
    private val signaling: SignalingClient,
    private val transport: ScreenTransport,
    private val journal: SessionJournal,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Что показывать бабушке прямо сейчас. */
    sealed interface Ui {
        data object Idle : Ui

        /**
         * Пришёл запрос помощи. `auto` — всегда true (песочница): согласие даётся
         * автоматически, интерактивных диалогов со стороны приложения нет.
         */
        data class Asked(val peerName: String, val note: String?, val auto: Boolean) : Ui
        data class Session(val peerName: String, val redacted: Boolean) : Ui
    }

    /**
     * Автосогласие для уже спаренного помощника. В тестовой песочнице это единственный
     * режим: запрос от спаренного устройства принимается без единого вопроса.
     * Спарить новое устройство удалённо всё так же нельзя.
     */
    var autoAccept: Boolean = true

    private val machine = SessionMachine(Role.GRANDMA)
    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var iceServers: List<IceServer> = emptyList()
    private var requestedAt = 0L
    private var consented = false
    private var screenShown = false
    private var nonceH: String? = null
    private var nonceG: String? = null
    private var fpH: String? = null

    /**
     * Разрешение на захват, полученное от системного диалога. Без него показ невозможен
     * в принципе — но и с ним он не начнётся раньше проверки MAC (см. `onAuthConfirm`).
     */
    var screenPermission: Intent? = null

    /**
     * Проекция уже получена и жива — значит новую сессию можно поднять без системного
     * диалога захвата. На это опирается сервис: из фона диалог на MIUI не запускается.
     */
    val captureReady: Boolean get() = transport.hasCapture()

    /** Полное освобождение захвата — при остановке сервиса. */
    fun releaseCapture() = transport.releaseCapture()

    /** Управляющие сообщения от помощника — указатель и подписи; их рисует оверлей. */
    var onControlMessage: ((String) -> Unit)? = null

    /**
     * Вызывается ровно перед стартом захвата. Сервису здесь нужно перевести себя
     * в тип `mediaProjection`: система требует этого до создания проекции — и только
     * тогда, когда разрешение уже выдано.
     */
    var onCaptureStarting: (() -> Unit)? = null

    init {
        // ICE-кандидаты уходят через сигналинг: сам транспорт о сервере ничего не знает.
        transport.setIceCandidateListener { candidateJson ->
            val sessionId = machine.sessionId ?: return@setIceCandidateListener
            signaling.send(Signal.Ice(sessionId, Json.parseToJsonElement(candidateJson)))
        }
        transport.setControlListener { message -> onControlMessage?.invoke(message) }
        // Бабушка остановила показ системными средствами — для нас это обычный «Стоп».
        transport.setScreenStoppedListener { stop() }
    }

    fun handle(signal: Signal) {
        when (signal) {
            is Signal.HelloOk -> iceServers = signal.iceServers

            is Signal.HelpRequest -> onHelpRequest(signal)

            is Signal.Offer -> scope.launch { onOffer(signal) }

            is Signal.AuthConfirm -> scope.launch { onAuthConfirm(signal) }

            is Signal.Ice -> scope.launch {
                if (machine.belongsToCurrent(signal.sessionId)) {
                    signal.candidate?.let { transport.addIceCandidate(it.toString()) }
                }
            }

            is Signal.SessionEnd -> {
                if (machine.belongsToCurrent(signal.sessionId)) finish(signal.reason, notifyPeer = false)
            }

            else -> Unit
        }
    }

    private fun onHelpRequest(signal: Signal.HelpRequest) {
        val sessionId = signal.sessionId ?: return
        if (machine.state != SessionMachine.State.IDLE) return
        machine.onHelpRequest(sessionId)
        requestedAt = signal.at ?: now()
        consented = false
        screenShown = false
        // Согласие автоматическое (песочница): сервис либо сразу шлёт consent-granted
        // (проекция уже жива), либо сначала получает системное разрешение на захват через
        // ConsentActivity-трамплин. Само согласие не даётся, пока захват не готов, —
        // иначе рукопожатие WebRTC обгонит получение проекции.
        _ui.value = Ui.Asked(pair.peerName, signal.note, auto = autoAccept)
    }

    /** Нажата «Разрешить». Единственный вход в сессию. */
    fun grant() {
        val sessionId = machine.sessionId ?: return
        machine.onConsent()
        consented = true
        signaling.send(Signal.ConsentGranted(sessionId))
    }

    /** «Не сейчас». Равнозначно молчанию: сессии не будет. */
    fun deny() {
        val sessionId = machine.sessionId ?: return
        signaling.send(Signal.ConsentDenied(sessionId))
        finish(EndReason.DECLINED, notifyPeer = false)
    }

    private suspend fun onOffer(signal: Signal.Offer) {
        // Правило 2: до согласия offer просто игнорируется — ответа помощник не получит.
        if (machine.state != SessionMachine.State.CONSENTED) return
        if (!machine.belongsToCurrent(signal.sessionId)) return

        fpH = PairAuth.fingerprintFromSdp(signal.sdp)
        nonceH = signal.nonceH
        nonceG = PairAuth.newNonce()

        val answerSdp = transport.answer(signal.sdp, iceServers)
        val macG = PairAuth.mac(
            secret = pair.secret,
            role = Role.GRANDMA,
            transcript = PairAuth.transcript(
                sessionId = signal.sessionId,
                nonceH = signal.nonceH,
                nonceG = nonceG!!,
                fpH = fpH!!,
                fpG = transport.localFingerprint,
            ),
        )
        signaling.send(Signal.Answer(signal.sessionId, answerSdp, nonceG!!, macG))
    }

    /** Правило 3: здесь и только здесь начинается показ экрана. */
    private suspend fun onAuthConfirm(signal: Signal.AuthConfirm) {
        if (!machine.belongsToCurrent(signal.sessionId)) return
        if (machine.state != SessionMachine.State.CONSENTED) return

        val expected = PairAuth.mac(
            secret = pair.secret,
            role = Role.HELPER,
            transcript = PairAuth.transcript(
                sessionId = signal.sessionId,
                nonceH = nonceH.orEmpty(),
                nonceG = nonceG.orEmpty(),
                fpH = fpH.orEmpty(),
                fpG = transport.localFingerprint,
            ),
        )

        if (!PairAuth.verify(expected, signal.macH)) {
            // Чужое устройство. Экран не захватывался и не будет — но в журнал это попадёт.
            finish(EndReason.AUTH_FAILED, notifyPeer = true)
            return
        }

        machine.onPeerAuthenticated()
        // Проекция либо уже поднята (переиспользуем — вторая и последующие сессии на MIUI),
        // либо разрешение только что получено из системного диалога (первая сессия).
        if (screenPermission == null && !transport.hasCapture()) {
            finish(EndReason.ERROR, notifyPeer = true)
            return
        }
        check(machine.canCaptureScreen) { "захват экрана вне состояния CONNECTED" }
        onCaptureStarting?.invoke()
        transport.startScreen(screenPermission)
        screenShown = true
        _ui.value = Ui.Session(pair.peerName, redacted = false)
    }

    /** Гашение на банковских приложениях. Инициатива всегда на стороне бабушки. */
    fun setRedacted(on: Boolean, packageName: String? = null) {
        if (!machine.canCaptureScreen) return
        scope.launch {
            transport.setRedacted(on, ForegroundAppWatcher.REDACT_REASON, packageName)
            _ui.value = Ui.Session(pair.peerName, redacted = on)
        }
    }

    /** Кнопка «Стоп». Должна отрабатывать меньше чем за секунду и при любом состоянии. */
    fun stop() = finish(EndReason.STOPPED_BY_GRANDMA, notifyPeer = true)

    /** Отправить помощнику служебное сообщение по data-каналу (напр. состояние службы). */
    fun sendControl(payload: String) = transport.sendControl(payload)

    /**
     * Помощник пропал из сети посреди сессии (закрыл вкладку, потерял связь).
     * Подстраховка: даже если session-end от сервера не дошёл, освобождаем захват и
     * возвращаемся в IDLE — иначе следующий запрос помощи был бы проигнорирован
     * (onHelpRequest выходит, пока state != IDLE), и переподключение бы не работало.
     */
    fun onPeerLost() {
        if (machine.state == SessionMachine.State.IDLE) return
        finish(EndReason.PEER_LOST, notifyPeer = false)
    }

    private fun finish(reason: String, notifyPeer: Boolean) {
        val sessionId = machine.sessionId ?: return

        // Сначала фиксируем факт: уведомляем вторую сторону и пишем журнал. Только потом
        // освобождаем WebRTC. Иначе сбой в нативном close() (а он бывает на некоторых
        // прошивках) унёс бы и session-end, и запись в журнал — сессия исчезла бы бесследно.
        if (notifyPeer) signaling.send(Signal.SessionEnd(sessionId, reason))
        journal.append(
            SessionJournal.Entry(
                sessionId = sessionId,
                peerName = pair.peerName,
                requestedAt = requestedAt,
                consented = consented,
                endedAt = now(),
                reason = reason,
                screenShown = screenShown,
            ),
        )

        // Помощник узнаёт о завершении из session-end через сигналинг (отправлен выше);
        // теперь освобождаем нативные ресурсы захвата.
        transport.close()
        screenPermission = null
        machine.onEnd()
        nonceH = null
        nonceG = null
        fpH = null
        _ui.value = Ui.Idle
    }
}
