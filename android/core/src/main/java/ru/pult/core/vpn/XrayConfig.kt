package ru.pult.core.vpn

import java.net.URLDecoder

/**
 * Разбор ссылки vless/vmess/trojan в конфиг Xray-core.
 *
 * Это «мозг» стадии 3 и он чистый — проверяется юнит-тестом без нативной библиотеки и без
 * сервера. Xray-core (нативный AAR) на вход берёт JSON; мы строим его из привычной ссылки,
 * которую семья получает из подписанного источника.
 *
 * Внутренний вход — локальный SOCKS-порт: tun2socks заворачивает туда трафик из TUN, а Xray
 * этот SOCKS проксирует наружу через выбранный сервер. Так работает вся связка v2rayNG-класса.
 */
object XrayConfig {

    const val LOCAL_SOCKS_PORT = 10808

    data class Server(
        val protocol: String,   // vless | vmess | trojan
        val id: String,         // uuid / password
        val host: String,
        val port: Int,
        val security: String,   // none | tls | reality
        val network: String,    // tcp | ws | grpc
        val sni: String?,
        val path: String?,
        val wsHost: String?,
        val flow: String?,
        val name: String,
    )

    /** Разобрать ссылку. Бросает, если формат не распознан — наверх, чтобы не тянуть кривой конфиг. */
    fun parse(uri: String): Server {
        val u = uri.trim()
        return when {
            u.startsWith("vless://") -> parseVless(u)
            u.startsWith("trojan://") -> parseTrojan(u)
            else -> error("не поддерживаемая ссылка: ${u.take(12)}…")
        }
    }

    // vless://uuid@host:port?encryption=none&security=tls&sni=..&type=ws&path=..&host=..&flow=..#name
    private fun parseVless(u: String): Server {
        val body = u.removePrefix("vless://")
        val hash = body.indexOf('#')
        val name = if (hash >= 0) dec(body.substring(hash + 1)) else "server"
        val main = if (hash >= 0) body.substring(0, hash) else body

        val q = main.indexOf('?')
        val auth = if (q >= 0) main.substring(0, q) else main
        val params = if (q >= 0) query(main.substring(q + 1)) else emptyMap()

        val at = auth.lastIndexOf('@')
        val id = auth.substring(0, at)
        val hostPort = auth.substring(at + 1)
        val (host, port) = splitHostPort(hostPort)

        return Server(
            protocol = "vless",
            id = id,
            host = host,
            port = port,
            security = params["security"] ?: "none",
            network = params["type"] ?: "tcp",
            sni = params["sni"] ?: params["host"],
            path = params["path"]?.let { dec(it) },
            wsHost = params["host"]?.let { dec(it) },
            flow = params["flow"],
            name = name,
        )
    }

    // trojan://password@host:port?security=tls&sni=..&type=ws&path=..#name
    private fun parseTrojan(u: String): Server {
        val body = u.removePrefix("trojan://")
        val hash = body.indexOf('#')
        val name = if (hash >= 0) dec(body.substring(hash + 1)) else "server"
        val main = if (hash >= 0) body.substring(0, hash) else body
        val q = main.indexOf('?')
        val auth = if (q >= 0) main.substring(0, q) else main
        val params = if (q >= 0) query(main.substring(q + 1)) else emptyMap()
        val at = auth.lastIndexOf('@')
        val (host, port) = splitHostPort(auth.substring(at + 1))
        return Server(
            protocol = "trojan",
            id = auth.substring(0, at),
            host = host,
            port = port,
            security = params["security"] ?: "tls",
            network = params["type"] ?: "tcp",
            sni = params["sni"] ?: params["host"],
            path = params["path"]?.let { dec(it) },
            wsHost = params["host"]?.let { dec(it) },
            flow = null,
            name = name,
        )
    }

    /** Итоговый JSON для Xray-core: локальный SOCKS-вход + выбранный сервер на выход. */
    fun toXrayJson(s: Server): String {
        val streamSettings = buildString {
            append("""{"network":"${s.network}","security":"${s.security}"""")
            if (s.security == "tls" || s.security == "reality") {
                append(""","tlsSettings":{"serverName":"${s.sni ?: s.host}","allowInsecure":false}""")
            }
            if (s.network == "ws") {
                val host = s.wsHost ?: s.sni ?: s.host
                append(""","wsSettings":{"path":"${s.path ?: "/"}","headers":{"Host":"$host"}}""")
            }
            append("}")
        }

        val outbound = when (s.protocol) {
            "trojan" -> """
                {"protocol":"trojan","settings":{"servers":[{"address":"${s.host}","port":${s.port},"password":"${s.id}"}]},"streamSettings":$streamSettings}
            """.trimIndent()
            else -> """
                {"protocol":"vless","settings":{"vnext":[{"address":"${s.host}","port":${s.port},"users":[{"id":"${s.id}","encryption":"none"${flowPart(s)}}]}]},"streamSettings":$streamSettings}
            """.trimIndent()
        }

        return """
        {
          "log": {"loglevel": "warning"},
          "inbounds": [
            {"tag":"socks","port":$LOCAL_SOCKS_PORT,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}}
          ],
          "outbounds": [ $outbound ]
        }
        """.trimIndent()
    }

    private fun flowPart(s: Server) = s.flow?.takeIf { it.isNotBlank() }?.let { ""","flow":"$it"""" } ?: ""

    private fun splitHostPort(hp: String): Pair<String, Int> {
        val i = hp.lastIndexOf(':')
        return hp.substring(0, i) to hp.substring(i + 1).toInt()
    }

    private fun query(q: String): Map<String, String> =
        q.split('&').mapNotNull {
            val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
