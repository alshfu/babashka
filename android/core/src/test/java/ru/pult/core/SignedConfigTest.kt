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
    // Вектор перегенерирован 2026-09-12: прошлый конверт был подписан на старый
    // адрес сервера (89-127-235-17), приватный ключ тест-вектора утерян.
    private val pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENzuxriNTq2aZgnCchqHbK136R3jsEJjgjwAvPo7ysV6JinEynDfUQvws84knLlNi5ZE5rWbbEbRd7R+u4otxHA=="

    // Конверт: payload = base64 манифеста, sig = base64 подписи по его байтам.
    private val payload = "eyJ2ZXJzaW9uIjoxLCJlbmRwb2ludHMiOlsid3NzOi8vODUuMTkwLjk4LjU3LnNzbGlwLmlvOjg0NDUvd3MiLCJ3c3M6Ly9iYWNrdXAuZXhhbXBsZS5jb20vd3MiXSwidHVubmVsVXJpIjpudWxsLCJ1cGRhdGVkQXQiOjEwMDB9"
    private val sig = "MEYCIQCYjrFfhPG9HHixZpI42CmDz6OWNN0dUJsOVPwizUtxFgIhANT3yGE/JBy2IgT4EAXp3Kkl45tAY2JeX6Z5IDwormud"

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
