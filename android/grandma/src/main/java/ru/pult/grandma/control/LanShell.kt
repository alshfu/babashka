package ru.pult.grandma.control

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Канал к LanAgent — app_process-процессу под shell (UID 2000) с TCP-портом
 * на loopback. В отличие от adbd-сессии: переживает выключение wireless
 * debugging (BankID требует её выключенной) и не виден BankID вообще.
 *
 * Порты: 47201 — JSON-lines команды (shell/tap/texts/bankid-login…),
 * 47202 — релей в localabstract-сокет «scrcpy» (каналы scrcpy-server).
 */
object LanShell {

    private const val TAG = "PultLan"
    private const val HOST = "127.0.0.1"
    const val PORT_CMD = 47201
    const val PORT_RELAY = 47202

    /** Жив ли LanAgent: ping за 2 с. */
    fun available(): Boolean = runCatching {
        request(JSONObject().put("t", "ping"), 2_000)?.optBoolean("ok") == true
    }.getOrDefault(false)

    /** Выполнить shell-команду (UID 2000). ok + stdout. */
    fun exec(cmd: String, timeoutMs: Long = 15000): Pair<Boolean, String> {
        val res = request(
            JSONObject().put("t", "shell").put("command", cmd),
            timeoutMs + 3_000,
        ) ?: return false to "lanagent unreachable"
        val ok = res.optBoolean("ok")
        return ok to res.optString("out", if (ok) "" else res.optString("err", "lanagent error"))
    }

    /** Канал к scrcpy-server через релей (вместо adbd localabstract). */
    fun openScrcpyChannel(): Socket? = runCatching {
        Socket().apply {
            connect(InetSocketAddress(HOST, PORT_RELAY), 4_000)
            tcpNoDelay = true
        }
    }.onFailure { Log.w(TAG, "relay connect: ${it.message}") }.getOrNull()

    private fun request(msg: JSONObject, timeoutMs: Long): JSONObject? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, PORT_CMD), 3_000)
            s.soTimeout = timeoutMs.toInt()
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), "UTF-8"))
            val r = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
            w.write(msg.toString()); w.write("\n"); w.flush()
            val line = r.readLine() ?: return null
            JSONObject(line)
        }
    }.onFailure { Log.w(TAG, "request: ${it.message}") }.getOrNull()
}
