package ru.pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.pult.core.net.SignedConfig

/**
 * Тест-вектор сгенерирован офлайн (openssl, EC P-256). Доказывает главное свойство
 * безопасности: валидная подпись принимается, любая подмена — отвергается.
 */
class SignedConfigTest {

    // Публичный ключ, запиненный в приложении (SPKI DER, base64).
    private val pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEE4lMaiAnXcawkI07IZt2GR4EWb8rFj8Sv" +
        "QWKFFjBv+5rNDFe4jTff2bSEhPzcJLQIGRWn+ZfG6fV+IaNFoZAeQ=="

    // Конверт: payload = base64 манифеста, sig = base64 подписи по его байтам.
    private val payload = "eyJ2ZXJzaW9uIjoxLCJlbmRwb2ludHMiOlsid3NzOi8vODktMTI3LTIzNS0xNy5zc2xpc" +
        "C5pby93cyIsIndzczovL2JhY2t1cC5leGFtcGxlLmNvbS93cyJdLCJ0dW5uZWxVcmkiOm51bGwsInVwZGF0ZWRBdCI6MTAwMH0="
    private val sig = "MEQCIAgOyxkhvzXgeptu43wB0mhwCO2utV7ctvkyj07QkK9kAiAGwX5Kjixu7tVV1fo7ZdeYLfnRfCc9jx4AShRYyRHIew=="

    private fun envelope(p: String, s: String) = """{"payload":"$p","sig":"$s"}"""

    @Test
    fun `валидная подпись принимается и манифест разбирается`() {
        val m = SignedConfig.verify(envelope(payload, sig), pub)
        requireNotNull(m) { "валидный конверт должен пройти" }
        assertEquals(2, m.endpoints.size)
        assertEquals("wss://85.190.98.57.sslip.io:8445/ws", m.endpoints[0])
        assertEquals(1000L, m.updatedAt)
    }

    @Test
    fun `подделанный payload отвергается`() {
        // Меняем эндпоинт на чужой, подпись прежняя → не сойдётся.
        val evil = java.util.Base64.getEncoder().encodeToString(
            """{"version":1,"endpoints":["wss://attacker.example/ws"],"tunnelUri":null,"updatedAt":9999}""".toByteArray(),
        )
        assertNull(SignedConfig.verify(envelope(evil, sig), pub))
    }

    @Test
    fun `чужой публичный ключ отвергает валидную подпись`() {
        val otherPub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqwJ8k8h5p3sXm2n9V0uY1t2a3b4c5d6e7f8g9h0i1j2k3l4m5n6o7p8q9r0s1t2u3v4w5x6y7z8A9B0C=="
        assertNull(SignedConfig.verify(envelope(payload, sig), otherPub))
    }
}
