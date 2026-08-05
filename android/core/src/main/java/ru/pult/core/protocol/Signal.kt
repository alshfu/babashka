package ru.pult.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Сообщения сигналинга (docs/protocol.md).
 *
 * Одно место на оба приложения: если бабушка и внук разойдутся в понимании протокола,
 * помощь просто не поднимется. Схема — protocol/messages.schema.json.
 */
@Serializable
sealed interface Signal {

    // ── Подключение ─────────────────────────────────────────────────────────

    @Serializable
    @SerialName("hello")
    data class Hello(
        val v: Int = PROTOCOL_VERSION,
        val pairId: String,
        val role: String,
        val deviceId: String,
        val journalTokenHash: String? = null,
    ) : Signal

    @Serializable
    @SerialName("hello-ok")
    data class HelloOk(
        val serverTime: Long = 0,
        val peerOnline: Boolean = false,
        val iceServers: List<IceServer> = emptyList(),
    ) : Signal

    @Serializable
    @SerialName("peer-state")
    data class PeerState(val online: Boolean) : Signal

    @Serializable
    @SerialName("register-push")
    data class RegisterPush(val token: String) : Signal

    // ── Отзыв доступа (инициирует внук, подпись проверяет телефон бабушки) ──────

    @Serializable
    @SerialName("revoke")
    data class Revoke(val nonce: String, val mac: String) : Signal

    @Serializable
    @SerialName("revoke-ack")
    data class RevokeAck(val ok: Boolean) : Signal

    // ── Запрос помощи и согласие ────────────────────────────────────────────

    /**
     * От помощника — без полей (кроме необязательного `note`).
     * От сервера бабушке — с `sessionId`, `from` и `at`.
     */
    @Serializable
    @SerialName("help-request")
    data class HelpRequest(
        val note: String? = null,
        val sessionId: String? = null,
        val from: String? = null,
        val at: Long? = null,
    ) : Signal

    @Serializable
    @SerialName("help-request-sent")
    data class HelpRequestSent(val sessionId: String, val peerOnline: Boolean = false) : Signal

    @Serializable
    @SerialName("consent-granted")
    data class ConsentGranted(val sessionId: String) : Signal

    @Serializable
    @SerialName("consent-denied")
    data class ConsentDenied(val sessionId: String, val reason: String = EndReason.DECLINED) : Signal

    // ── WebRTC ──────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("offer")
    data class Offer(val sessionId: String, val sdp: String, val nonceH: String) : Signal

    @Serializable
    @SerialName("answer")
    data class Answer(
        val sessionId: String,
        val sdp: String,
        val nonceG: String,
        val macG: String,
    ) : Signal

    @Serializable
    @SerialName("auth-confirm")
    data class AuthConfirm(val sessionId: String, val macH: String) : Signal

    @Serializable
    @SerialName("ice")
    data class Ice(val sessionId: String, val candidate: JsonElement? = null) : Signal

    @Serializable
    @SerialName("session-end")
    data class SessionEnd(val sessionId: String, val reason: String) : Signal

    // ── Шлюз BankID-диплинков (канал /link, WebRTC-сессия не нужна) ────────────

    /** Сервер → бабушка: открыть BankID-диплинк (autostarttoken от приложения шлюза). */
    @Serializable
    @SerialName("deeplink")
    data class Deeplink(val url: String) : Signal

    /** Сервер → бабушка: начать/завершить lowlat-трансляцию экрана (канал /link). */
    @Serializable
    @SerialName("screencast")
    data class Screencast(val on: Boolean) : Signal

    /** Бабушка → сервер (→ приложение шлюза): итог подписания. stage: opened|signed|failed. */
    @Serializable
    @SerialName("deeplink-status")
    data class DeeplinkStatus(val ok: Boolean, val stage: String, val err: String = "") : Signal

    // ── Служебное ───────────────────────────────────────────────────────────

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String? = null) : Signal

    @Serializable
    @SerialName("ping")
    data object Ping : Signal

    @Serializable
    @SerialName("pong")
    data object Pong : Signal

    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}

@Serializable
data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

/** Причины завершения сессии. Отказ и молчание фиксируются наравне с состоявшейся помощью. */
object EndReason {
    const val STOPPED_BY_GRANDMA = "stopped-by-grandma"
    const val STOPPED_BY_HELPER = "stopped-by-helper"
    const val DECLINED = "declined"
    const val NO_ANSWER = "no-answer"
    const val CANCELLED = "cancelled"
    const val AUTH_FAILED = "auth-failed"
    const val PEER_LOST = "peer-lost"
    const val TIMEOUT = "timeout"
    const val ERROR = "error"
}

object Role {
    const val GRANDMA = "grandma"
    const val HELPER = "helper"
}

/**
 * Разбор и сборка сообщений. Неизвестные поля игнорируются: сервер новее клиента —
 * не повод отказать бабушке в помощи.
 */
object SignalCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(signal: Signal): String = json.encodeToString(Signal.serializer(), signal)

    /** null — сообщение не разобрано; такие просто игнорируются, соединение не рвётся. */
    fun decodeOrNull(text: String): Signal? = runCatching {
        json.decodeFromString(Signal.serializer(), text)
    }.getOrNull()
}
