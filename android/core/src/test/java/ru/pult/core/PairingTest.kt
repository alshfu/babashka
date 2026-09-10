package ru.pult.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
