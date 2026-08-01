package ru.pult.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pult.core.calls.PhoneNumbers
import ru.pult.core.pairing.InMemoryPairStore
import ru.pult.core.pairing.PairRecord
import ru.pult.core.pairing.PairingPacket
import ru.pult.core.protocol.PairAuth

class PairingPacketTest {

    private val secret = PairAuth.newSecret()

    @Test
    fun `пакет переживает кодирование и декодирование`() {
        val packet = PairingPacket.create("pair-1", secret, "Петя", "wss://pult.example/ws")
        val decoded = PairingPacket.decode(packet.encode())

        assertEquals("pair-1", decoded.pairId)
        assertEquals("Петя", decoded.name)
        assertArrayEquals(secret, decoded.secret())
    }

    @Test
    fun `повреждённый пакет отвергается, а не принимается наполовину`() {
        runCatching { PairingPacket.decode("не-пакет") }
            .onSuccess { error("мусор обязан отвергаться") }

        val short = PairingPacket(
            pairId = "p",
            secretBase64url = PairAuth.base64url(ByteArray(8)),
            name = "Петя",
        )
        runCatching { PairingPacket.decode(short.encode()) }
            .onSuccess { error("короткий секрет обязан отвергаться") }
    }

    @Test
    fun `запись пары не печатает секрет в логи`() {
        val record = PairRecord("pair-1", secret, "Петя", "wss://x/ws", 0)
        val text = record.toString()
        assertFalse(text.contains(PairAuth.base64url(secret)))
        assertTrue(text.contains("Петя"))
    }

    @Test
    fun `забыть помощника стирает секрет`() {
        val store = InMemoryPairStore()
        store.save(PairRecord("pair-1", secret.copyOf(), "Петя", "wss://x/ws", 0))
        assertTrue(store.load() != null)

        store.clear()
        assertNull(store.load())
    }
}

class PhoneNumbersTest {

    @Test
    fun `один номер в разных записях считается одним`() {
        val forms = listOf("+7 (999) 123-45-67", "89991234567", "79991234567", "9991234567")
        val normalized = forms.map(PhoneNumbers::normalize).toSet()
        assertEquals("разные записи одного номера должны совпадать", 1, normalized.size)
    }

    @Test
    fun `экстренные номера проходят всегда`() {
        assertTrue(PhoneNumbers.isEmergency("112"))
        assertTrue(PhoneNumbers.shouldAllow("112", whitelist = emptyList(), strict = true))
        assertTrue(PhoneNumbers.shouldAllow("103", whitelist = emptyList(), strict = true))
    }

    @Test
    fun `в строгом режиме проходит только белый список`() {
        val whitelist = listOf("+7 999 123-45-67")
        assertTrue(PhoneNumbers.shouldAllow("89991234567", whitelist, strict = true))
        assertFalse(PhoneNumbers.shouldAllow("89990000000", whitelist, strict = true))
    }

    @Test
    fun `в мягком режиме чужой номер проходит с меткой`() {
        assertTrue(PhoneNumbers.shouldAllow("89990000000", emptyList(), strict = false))
    }

    @Test
    fun `скрытый номер в строгом режиме не проходит`() {
        assertTrue(PhoneNumbers.isHidden(null))
        assertTrue(PhoneNumbers.isHidden(""))
        assertFalse(PhoneNumbers.shouldAllow(null, listOf("89991234567"), strict = true))
    }

    @Test
    fun `нормализация пустых значений`() {
        assertNull(PhoneNumbers.normalize(null))
        assertNull(PhoneNumbers.normalize("   "))
        assertNull(PhoneNumbers.normalize("абв"))
    }
}
