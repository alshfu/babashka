package ru.pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pult.core.protocol.EndReason
import ru.pult.core.protocol.Role
import ru.pult.core.protocol.Signal
import ru.pult.core.protocol.SignalCodec

class SignalCodecTest {

    @Test
    fun `hello сериализуется с типом и версией`() {
        val text = SignalCodec.encode(
            Signal.Hello(pairId = "p1", role = Role.GRANDMA, deviceId = "d1"),
        )
        assertTrue(text.contains("\"t\":\"hello\""))
        assertTrue(text.contains("\"v\":1"))
        assertTrue(text.contains("\"role\":\"grandma\""))
    }

    @Test
    fun `неизвестные поля от более нового сервера не ломают разбор`() {
        val signal = SignalCodec.decodeOrNull(
            """{"t":"hello-ok","serverTime":1,"peerOnline":true,"iceServers":[],"новоеПоле":42}""",
        )
        assertTrue(signal is Signal.HelloOk)
        assertTrue((signal as Signal.HelloOk).peerOnline)
    }

    @Test
    fun `битое сообщение даёт null, а не исключение`() {
        assertNull(SignalCodec.decodeOrNull("{не json"))
        assertNull(SignalCodec.decodeOrNull("""{"t":"такого-типа-нет"}"""))
        assertNull(SignalCodec.decodeOrNull(""))
    }

    @Test
    fun `запрос помощи от сервера приходит с sessionId, от помощника — без`() {
        val fromHelper = SignalCodec.decodeOrNull("""{"t":"help-request","note":"почта"}""")
        assertEquals("почта", (fromHelper as Signal.HelpRequest).note)
        assertNull(fromHelper.sessionId)

        val fromServer = SignalCodec.decodeOrNull(
            """{"t":"help-request","sessionId":"s1","from":"helper","at":123}""",
        ) as Signal.HelpRequest
        assertEquals("s1", fromServer.sessionId)
        assertEquals(Role.HELPER, fromServer.from)
    }

    @Test
    fun `круговой обход всех типов сессии`() {
        val messages = listOf(
            Signal.HelpRequestSent("s1", peerOnline = true),
            Signal.ConsentGranted("s1"),
            Signal.ConsentDenied("s1"),
            Signal.Offer("s1", "v=0", "nh"),
            Signal.Answer("s1", "v=0", "ng", "mac"),
            Signal.AuthConfirm("s1", "mac"),
            Signal.SessionEnd("s1", EndReason.STOPPED_BY_GRANDMA),
            Signal.Error("rate-limited", "слишком часто"),
            Signal.Ping,
            Signal.Pong,
        )
        messages.forEach { original ->
            assertEquals(original, SignalCodec.decodeOrNull(SignalCodec.encode(original)))
        }
    }
}
