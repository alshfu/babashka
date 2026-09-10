package ru.pult.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ru.pult.core.protocol.Signal
import ru.pult.core.protocol.SignalCodec
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Клиент сигналинга: WebSocket с автоматическим переподключением.
 *
 * Переподключение здесь не «улучшение», а требование ТЗ (п.3): мобильная сеть у бабушки
 * рвётся регулярно, а телефон, «потерявший» сигналинг, выглядит для внука как «бабушка
 * не в сети» — то есть помощь недоступна, и никто об этом не знает.
 */
class SignalingClient(
    endpoints: List<String>,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
) {

    /** Совместимость: один адрес — частный случай списка из одного эндпоинта. */
    constructor(
        url: String,
        identity: Identity,
        scope: CoroutineScope,
        client: OkHttpClient = defaultClient(),
    ) : this(listOf(url), identity, scope, client)

    /**
     * Список адресов сигналинга. РКН активно режет known-адреса, поэтому телефон не должен
     * биться в один заблокированный вход: при каждом обрыве пробуем СЛЕДУЮЩИЙ эндпоинт
     * (другой домен/CDN/порт). Список обновляется извне (см. setEndpoints) из подписанного
     * источника — «где сегодня брать связь».
     */
    @Volatile
    private var endpoints: List<String> = endpoints.ifEmpty { error("нужен хотя бы один эндпоинт") }

    @Volatile
    private var endpointIndex = 0

    private val currentUrl: String get() = endpoints[endpointIndex % endpoints.size]

    /** Обновить список эндпоинтов на лету (после загрузки из внешнего источника). */
    fun setEndpoints(list: List<String>) {
        if (list.isNotEmpty()) {
            endpoints = list
            endpointIndex = 0
        }
    }

    data class Identity(
        val pairId: String,
        val role: String,
        val deviceId: String,
        val journalTokenHash: String? = null,
        // Подпись устройства для панели оператора (label) и модель — уходят в hello.
        val label: String? = null,
        val model: String? = null,
        // ОС и IP-адрес устройства — для списка A-appar i B-appen.
        val os: String? = null,
        val ip: String? = null,
    )

    enum class Link { OFFLINE, CONNECTING, ONLINE }

    private val _incoming = MutableSharedFlow<Signal>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Signal> = _incoming.asSharedFlow()

    private val _link = MutableStateFlow(Link.OFFLINE)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _peerOnline = MutableStateFlow(false)
    val peerOnline: StateFlow<Boolean> = _peerOnline.asStateFlow()

    private var socket: WebSocket? = null
    private var attempt = 0
    private var stopped = false
    private var reconnectJob: Job? = null

    fun connect() {
        stopped = false
        openSocket()
    }

    fun close() {
        stopped = true
        reconnectJob?.cancel()
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        _link.value = Link.OFFLINE
    }

    fun send(signal: Signal): Boolean = socket?.send(SignalCodec.encode(signal)) ?: false

    /**
     * Мгновенное переподключение по смене сети (ConnectivityManager-сторож в PultService).
     * Сбрасывает backoff до минимума и пробует немедленно, минуя растущие паузы. Живой
     * сокет (ONLINE) не трогаем: ложные срабатывания колбэка сети не должны рвать рабочую
     * связь, а мёртвый сокет сам рухнет по ping. Ротация эндпоинтов не меняется.
     */
    fun forceReconnectNow() {
        if (stopped || _link.value == Link.ONLINE) return
        attempt = 0
        reconnectJob?.cancel()
        reconnectJob = scope.launch { openSocket() }
    }

    private fun openSocket() {
        _link.value = Link.CONNECTING
        val request = Request.Builder().url(currentUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                webSocket.send(
                    SignalCodec.encode(
                        Signal.Hello(
                            pairId = identity.pairId,
                            role = identity.role,
                            deviceId = identity.deviceId,
                            journalTokenHash = identity.journalTokenHash,
                            label = identity.label,
                            model = identity.model,
                            os = identity.os,
                            ip = identity.ip,
                        ),
                    ),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                android.util.Log.i("PultWs", "onMessage at ${System.currentTimeMillis()} type=${text.take(40)}")
                // Неразобранное сообщение молча пропускаем: рвать связь бабушке из-за
                // непонятного кадра — худшее, что можно сделать посреди помощи.
                val signal = SignalCodec.decodeOrNull(text) ?: return
                when (signal) {
                    is Signal.HelloOk -> {
                        _link.value = Link.ONLINE
                        _peerOnline.value = signal.peerOnline
                    }
                    is Signal.PeerState -> _peerOnline.value = signal.online
                    else -> Unit
                }
                scope.launch { _incoming.emit(signal) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    /**
     * Экспоненциальная пауза с джиттером + перебор эндпоинтов. После каждого обрыва берём
     * СЛЕДУЮЩИЙ адрес: если текущий заблокирован РКН, телефон сам находит рабочий вход, а не
     * долбится в стену. Бэкофф растёт по числу попыток, но после полного круга по списку —
     * не быстрее, чтобы не спамить.
     */
    private fun scheduleReconnect() {
        _link.value = Link.OFFLINE
        _peerOnline.value = false
        if (stopped) return
        if (endpoints.size > 1) endpointIndex = (endpointIndex + 1) % endpoints.size
        val delayMs = min(MAX_BACKOFF_MS, 1000L shl min(attempt, 6)) * (50 + Random.nextInt(50)) / 100
        attempt += 1
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!stopped) openSocket()
        }
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val MAX_BACKOFF_MS = 60_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Частый ping: над капризным Wi-Fi телефон должен сам быстро заметить мёртвый
            // сокет и переподключиться, иначе окажется «в сети» для сервера, но глухим.
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
