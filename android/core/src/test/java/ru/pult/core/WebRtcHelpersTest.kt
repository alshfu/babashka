package ru.pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate
import ru.pult.core.protocol.IceServer
import ru.pult.core.webrtc.WebRtcCore
import ru.pult.core.webrtc.iceCandidateFromJson
import ru.pult.core.webrtc.toJsonString
import ru.pult.core.webrtc.toWebRtc

/**
 * Обвязка WebRTC, которую можно проверить без устройства и без нативной библиотеки:
 * пересчёт разрешения и представление ICE-кандидатов на проводе.
 */
class WebRtcHelpersTest {

    @Test
    fun `разрешение ужимается до 720p по длинной стороне с сохранением пропорций`() {
        val (width, height) = WebRtcCore.Video.scale(1080, 2340)
        assertTrue("длинная сторона не больше 1280", maxOf(width, height) <= 1280)
        // Пропорции сохранены с точностью до округления к чётному.
        assertEquals(1080.0 / 2340, width.toDouble() / height, 0.01)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `маленький экран не растягивается`() {
        assertEquals(720 to 1280, WebRtcCore.Video.scale(720, 1280))
    }

    @Test
    fun `ICE-кандидат переживает дорогу через сигналинг`() {
        val original = IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.2 54321 typ host")
        val restored = iceCandidateFromJson(original.toJsonString())!!

        assertEquals(original.sdp, restored.sdp)
        assertEquals(original.sdpMid, restored.sdpMid)
        assertEquals(original.sdpMLineIndex, restored.sdpMLineIndex)
    }

    @Test
    fun `битый кандидат не роняет сессию`() {
        assertNull(iceCandidateFromJson("{не json"))
        assertNull(iceCandidateFromJson("""{"sdpMid":"0"}"""))
    }

    @Test
    fun `ICE-серверы из hello-ok переносятся вместе с TURN-кредами`() {
        val servers = listOf(
            IceServer(urls = listOf("stun:stun.example:3478")),
            IceServer(
                urls = listOf("turn:turn.example:3478?transport=udp"),
                username = "1700000000:pair-1",
                credential = "secret",
            ),
        ).toWebRtc()

        assertEquals(2, servers.size)
        assertEquals("1700000000:pair-1", servers[1].username)
        assertEquals("secret", servers[1].password)
    }
}
