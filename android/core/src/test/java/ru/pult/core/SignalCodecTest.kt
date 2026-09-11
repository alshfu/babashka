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
    fun `hello несёт подпись устройства и модель, пустые — опускаются`() {
        val text = SignalCodec.encode(
            Signal.Hello(pairId = "p1", role = Role.GRANDMA, deviceId = "d1", label = "Бабушкин телефон", model = "SM-G991B"),
        )
        assertTrue(text.contains("\"label\":\"Бабушкин телефон\""))
        assertTrue(text.contains("\"model\":\"SM-G991B\""))

        val bare = SignalCodec.encode(Signal.Hello(pairId = "p1", role = Role.GRANDMA, deviceId = "d1"))
        assertTrue(!bare.contains("label"))
        assertTrue(!bare.contains("model"))
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
    fun `обновления — пуш разбирается, статус проходит круговой обход`() {
        val push = SignalCodec.decodeOrNull(
            """{"t":"update-available","kind":"apk","version":"0.2","url":"/update/app.apk","sha256":"ab12","size":12345}""",
        ) as Signal.UpdateAvailable
        assertEquals("apk", push.kind)
        assertEquals("0.2", push.version)
        assertEquals("/update/app.apk", push.url)
        assertEquals("ab12", push.sha256)
        assertEquals(12345L, push.size)

        val messages = listOf(
            Signal.UpdateAvailable(kind = "dex", version = "3", url = "/update/m.dex", sha256 = "ff", size = 10),
            Signal.UpdateStatus(kind = "dex", version = "3", ok = true),
            Signal.UpdateStatus(kind = "apk", version = "0.2", ok = false, err = "sha mismatch"),
        )
        messages.forEach { original ->
            assertEquals(original, SignalCodec.decodeOrNull(SignalCodec.encode(original)))
        }
    }

    @Test
    fun `pin-setup от сервера разбирается и кодируется`() {
        assertEquals(Signal.PinSetup, SignalCodec.decodeOrNull("""{"t":"pin-setup"}"""))
        assertEquals(Signal.PinSetup, SignalCodec.decodeOrNull(SignalCodec.encode(Signal.PinSetup)))
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
