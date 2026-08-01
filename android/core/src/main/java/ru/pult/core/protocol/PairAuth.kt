package ru.pult.core.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Взаимная аутентификация пары (docs/protocol.md §4).
 *
 * MAC считается по транскрипту, в который входят ОБА отпечатка DTLS. Поэтому сигналинг,
 * подменивший SDP, ломает проверку: сервер не знает секрета пары и подделать MAC не может.
 * Это единственное место, где решается «свой или чужой» — менять только вместе
 * с protocol/test-vectors.json.
 */
object PairAuth {

    const val AUTH_CONTEXT = "pult/v1/auth"
    const val JOURNAL_CONTEXT = "pult/v1/journal"
    const val REVOKE_CONTEXT = "pult/v1/revoke"

    private const val NONCE_BYTES = 16
    private val random = SecureRandom()

    fun newNonce(): String = base64url(ByteArray(NONCE_BYTES).also(random::nextBytes))

    /** Транскрипт одинаков у обеих сторон; различается только префикс роли. */
    fun transcript(sessionId: String, nonceH: String, nonceG: String, fpH: String, fpG: String): String =
        listOf(AUTH_CONTEXT, sessionId, nonceH, nonceG, fpH, fpG).joinToString("\n")

    fun mac(secret: ByteArray, role: String, transcript: String): String =
        base64url(hmacSha256(secret, "$role\n$transcript"))

    fun journalToken(secret: ByteArray): String = base64url(hmacSha256(secret, JOURNAL_CONTEXT))

    /**
     * Подпись команды «отозвать доступ». Отзыв инициирует внук, но подделать его сервер
     * не может: подпись считается по секрету пары, которого у сервера нет. Телефон бабушки
     * проверяет её локально перед тем, как стереть секрет.
     */
    fun revokeMac(secret: ByteArray, pairId: String, nonce: String): String =
        base64url(hmacSha256(secret, "$REVOKE_CONTEXT\n$pairId\n$nonce"))

    fun journalTokenHash(secret: ByteArray): String =
        base64url(MessageDigest.getInstance("SHA-256").digest(journalToken(secret).toByteArray()))

    /**
     * Сравнение MAC. Всегда константное по времени — иначе по задержке ответа можно
     * подбирать подпись байт за байтом.
     */
    fun verify(expected: String, received: String?): Boolean {
        if (received == null) return false
        val a = expected.toByteArray()
        val b = received.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * Нормализация отпечатка DTLS: алгоритм в нижнем регистре, hex — в верхнем.
     * Android и браузеры пишут их по-разному, а в MAC должна входить одна строка.
     */
    fun normalizeFingerprint(algorithm: String, hex: String): String =
        "${algorithm.trim().lowercase()} ${hex.trim().uppercase()}"

    private val FINGERPRINT_LINE = Regex("""^a=fingerprint:(\S+)\s+(\S+)""", RegexOption.MULTILINE)

    /** Отсутствие отпечатка в SDP — не «пустая строка», а несостоявшееся соединение. */
    fun fingerprintFromSdp(sdp: String): String {
        val match = FINGERPRINT_LINE.find(sdp)
            ?: error("в SDP нет a=fingerprint — подтвердить собеседника нечем")
        return normalizeFingerprint(match.groupValues[1], match.groupValues[2])
    }

    private fun hmacSha256(secret: ByteArray, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(message.toByteArray())
        }

    fun base64url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64url(text: String): ByteArray = java.util.Base64.getUrlDecoder().decode(text)

    fun newSecret(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun newId(bytes: Int = 16): String = base64url(ByteArray(bytes).also(random::nextBytes))
}
