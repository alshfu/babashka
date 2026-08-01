package ru.pult.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pult.core.protocol.PairAuth
import java.io.File

/**
 * Kotlin-реализация крипто пары проверяется теми же векторами, что сервер и веб-панель
 * (protocol/test-vectors.json). Разойдутся — телефон бабушки и панель внука перестанут
 * подтверждать пару, то есть помощь не поднимется вообще.
 */
class PairAuthTest {

    private val vectors: JsonObject = Json.parseToJsonElement(vectorsFile().readText()) as JsonObject
    private val secret = PairAuth.fromBase64url(vectors["pairSecretBase64url"]!!.jsonPrimitive.content)

    @Test
    fun `секрет пары 32 байта`() {
        assertEquals(32, secret.size)
        assertEquals(32, PairAuth.newSecret().size)
    }

    @Test
    fun `нормализация отпечатка DTLS`() {
        vectors["fingerprintNormalization"]!!.jsonArray.forEach { element ->
            val item = element as JsonObject
            assertEquals(
                item["expected"]!!.jsonPrimitive.content,
                PairAuth.normalizeFingerprint(
                    item["algorithm"]!!.jsonPrimitive.content,
                    item["hex"]!!.jsonPrimitive.content,
                ),
            )
        }
    }

    @Test
    fun `MAC обеих сторон совпадают с векторами`() {
        vectors["auth"]!!.jsonArray.forEach { element ->
            val item = element as JsonObject
            fun field(name: String) = item[name]!!.jsonPrimitive.content

            val transcript = PairAuth.transcript(
                sessionId = field("sessionId"),
                nonceH = field("nonceH"),
                nonceG = field("nonceG"),
                fpH = field("fpH"),
                fpG = field("fpG"),
            )
            assertEquals(field("transcript"), transcript)
            assertEquals(field("macG"), PairAuth.mac(secret, "grandma", transcript))
            assertEquals(field("macH"), PairAuth.mac(secret, "helper", transcript))
        }
    }

    @Test
    fun `токен журнала и его хеш`() {
        assertEquals(vectors["journalToken"]!!.jsonPrimitive.content, PairAuth.journalToken(secret))
        // Хеш нужен серверу: сам токен он вычислить не может, значит и журнал не прочитает.
        assertNotEquals(PairAuth.journalToken(secret), PairAuth.journalTokenHash(secret))
    }

    @Test
    fun `подмена отпечатка ломает MAC — это и есть защита от MITM на сигналинге`() {
        val item = vectors["auth"]!!.jsonArray.first() as JsonObject
        fun field(name: String) = item[name]!!.jsonPrimitive.content

        val tampered = PairAuth.transcript(
            sessionId = field("sessionId"),
            nonceH = field("nonceH"),
            nonceG = field("nonceG"),
            fpH = "sha-256 DE:AD:BE:EF", // сервер подсунул свой ключ
            fpG = field("fpG"),
        )
        assertFalse(PairAuth.verify(field("macG"), PairAuth.mac(secret, "grandma", tampered)))
    }

    @Test
    fun `подпись отзыва совпадает с вектором`() {
        val revoke = vectors["revoke"]!!.let { it as JsonObject }
        assertEquals(
            revoke["mac"]!!.jsonPrimitive.content,
            PairAuth.revokeMac(
                secret,
                revoke["pairId"]!!.jsonPrimitive.content,
                revoke["nonce"]!!.jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun `роли не взаимозаменяемы`() {
        val transcript = PairAuth.transcript("s", "nh", "ng", "fh", "fg")
        assertNotEquals(
            PairAuth.mac(secret, "grandma", transcript),
            PairAuth.mac(secret, "helper", transcript),
        )
    }

    @Test
    fun `verify устойчив к null и разной длине`() {
        val mac = PairAuth.mac(secret, "helper", "x")
        assertTrue(PairAuth.verify(mac, mac))
        assertFalse(PairAuth.verify(mac, null))
        assertFalse(PairAuth.verify(mac, mac.dropLast(1)))
        assertFalse(PairAuth.verify(mac, mac.dropLast(1) + "A"))
    }

    @Test
    fun `отпечаток вынимается из SDP, а его отсутствие — ошибка`() {
        val sdp = "v=0\r\na=fingerprint:SHA-256 aa:bb:cc\r\na=setup:actpass"
        assertEquals("sha-256 AA:BB:CC", PairAuth.fingerprintFromSdp(sdp))
        runCatching { PairAuth.fingerprintFromSdp("v=0") }
            .onSuccess { error("SDP без отпечатка обязан отвергаться") }
    }

    @Test
    fun `nonce не повторяется`() {
        val seen = HashSet<String>()
        repeat(1000) { assertTrue("nonce повторился", seen.add(PairAuth.newNonce())) }
        assertEquals(16, PairAuth.fromBase64url(PairAuth.newNonce()).size)
    }

    private fun vectorsFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/test-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("не найден protocol/test-vectors.json")
    }
}
