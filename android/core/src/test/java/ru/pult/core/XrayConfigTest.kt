package ru.pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pult.core.vpn.XrayConfig

/**
 * Проверяем «мозг» стадии 3 без нативной библиотеки и без сервера: ссылка → поля → JSON.
 * Корректность самого разбора верифицируема здесь; примет ли JSON Xray — проверяется на сервере.
 */
class XrayConfigTest {

    @Test
    fun `vless ws tls разбирается в поля`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443" +
            "?encryption=none&security=tls&sni=edge.example.com&type=ws&path=%2Fpult&host=edge.example.com#Berlin"
        val s = XrayConfig.parse(uri)

        assertEquals("vless", s.protocol)
        assertEquals("11111111-2222-3333-4444-555555555555", s.id)
        assertEquals("edge.example.com", s.host)
        assertEquals(443, s.port)
        assertEquals("tls", s.security)
        assertEquals("ws", s.network)
        assertEquals("/pult", s.path)
        assertEquals("edge.example.com", s.sni)
        assertEquals("Berlin", s.name)
    }

    @Test
    fun `в JSON есть локальный SOCKS-вход и сервер на выход`() {
        val uri = "vless://uuid-x@1.2.3.4:8443?security=reality&type=grpc&sni=www.microsoft.com&flow=xtls-rprx-vision#S"
        val json = XrayConfig.toXrayJson(XrayConfig.parse(uri))

        assertTrue(json.contains("\"protocol\":\"socks\""))
        assertTrue(json.contains("\"port\":${XrayConfig.LOCAL_SOCKS_PORT}"))
        assertTrue(json.contains("\"protocol\":\"vless\""))
        assertTrue(json.contains("\"address\":\"1.2.3.4\""))
        assertTrue(json.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(json.contains("\"security\":\"reality\""))
    }

    @Test
    fun `trojan разбирается`() {
        val s = XrayConfig.parse("trojan://secret@t.example.com:443?security=tls&type=tcp#T")
        assertEquals("trojan", s.protocol)
        assertEquals("secret", s.id)
        assertEquals(443, s.port)
        assertTrue(XrayConfig.toXrayJson(s).contains("\"protocol\":\"trojan\""))
    }
}
