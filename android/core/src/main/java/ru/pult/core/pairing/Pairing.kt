package ru.pult.core.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.pult.core.protocol.PairAuth

/**
 * Пакет спаривания — то, что физически переезжает с телефона на телефон
 * по NFC или через QR при личной встрече (docs/pairing.md).
 *
 * Через сеть он не передаётся никогда и ни в каком виде. Отсюда же следует, что здесь
 * нет и не может быть «ссылки для восстановления»: любой такой путь воспроизводит
 * атаку «внук в беде, продиктуйте код», от которой продукт и защищает.
 */
@Serializable
data class PairingPacket(
    val v: Int = 1,
    @SerialName("pid") val pairId: String,
    @SerialName("sec") val secretBase64url: String,
    val name: String = "Помощник",
    val url: String? = null,
) {
    fun secret(): ByteArray = PairAuth.fromBase64url(secretBase64url)

    fun encode(): String = PairAuth.base64url(json.encodeToString(serializer(), this).toByteArray())

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun create(pairId: String, secret: ByteArray, name: String, url: String?): PairingPacket =
            PairingPacket(
                pairId = pairId,
                secretBase64url = PairAuth.base64url(secret),
                name = name,
                url = url,
            )

        fun decode(packed: String): PairingPacket {
            val packet = json.decodeFromString(serializer(), String(PairAuth.fromBase64url(packed)))
            require(packet.v == 1) { "неизвестная версия пакета спаривания" }
            require(packet.secret().size == SECRET_BYTES) { "секрет пары повреждён" }
            return packet
        }

        const val SECRET_BYTES = 32

        /** QR живёт полторы минуты и одноразовый: сфотографировать «на потом» бесполезно. */
        const val QR_TTL_MS = 90_000L
    }
}

/** Пара, как она хранится на устройстве. Секрет здесь — и он не должен покидать защищённое хранилище. */
data class PairRecord(
    val pairId: String,
    val secret: ByteArray,
    val peerName: String,
    val signalingUrl: String,
    val createdAt: Long,
) {
    val journalToken: String get() = PairAuth.journalToken(secret)
    val journalTokenHash: String get() = PairAuth.journalTokenHash(secret)

    /** Короткий отпечаток пары для показа человеку — секрет из него не восстанавливается. */
    val shortId: String get() = pairId.take(8)

    // toString переопределён намеренно: иначе секрет уедет в первый же лог или стектрейс.
    override fun toString(): String = "PairRecord(pairId=$shortId, peerName=$peerName)"

    override fun equals(other: Any?): Boolean =
        other is PairRecord && other.pairId == pairId && other.secret.contentEquals(secret)

    override fun hashCode(): Int = pairId.hashCode() * 31 + secret.contentHashCode()
}

/**
 * Хранилище пары. Реализация на устройстве — поверх Android Keystore
 * (`EncryptedSharedPreferences`), в тестах — в памяти.
 */
interface PairStore {
    fun load(): PairRecord?
    fun save(record: PairRecord)

    /** «Забыть помощника»: после этого ни одна сессия не поднимется — проверка локальная. */
    fun clear()
}

class InMemoryPairStore(private var record: PairRecord? = null) : PairStore {
    override fun load(): PairRecord? = record
    override fun save(record: PairRecord) {
        this.record = record
    }

    override fun clear() {
        record?.secret?.fill(0)
        record = null
    }
}
