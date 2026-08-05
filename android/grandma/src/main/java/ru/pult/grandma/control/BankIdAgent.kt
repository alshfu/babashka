package ru.pult.grandma.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Завершение входа BankID на самом телефоне — без Mac, панели, LanAgent и Shizuku.
 *
 * Shell-уровень даёт наш собственный ADB-клиент ([AdbShell]): команды выполняются как
 * UID 2000 через wireless debugging (TLS, ключ приложения). Открытие BankID — `am start`
 * (обходит BAL), тексты/фокус — uiautomator/dumpsys, тачи — control-сокет scrcpy-server,
 * который мы сами спавним (app_process от shell) и к которому подключаемся напрямую
 * через localabstract (abstract namespace доступен любому процессу устройства).
 *
 * Координаты и последовательность — проверенные в бою (бывший scripts/scrcpy-lan.mjs).
 */
object BankIdAgent {

    // Раскладка PIN-pad BankID в долях экрана (замеры 720×1600).
    private val KEYS = mapOf(
        "1" to doubleArrayOf(0.167, 0.629), "2" to doubleArrayOf(0.501, 0.629), "3" to doubleArrayOf(0.835, 0.629),
        "4" to doubleArrayOf(0.167, 0.719), "5" to doubleArrayOf(0.501, 0.719), "6" to doubleArrayOf(0.835, 0.719),
        "7" to doubleArrayOf(0.167, 0.809), "8" to doubleArrayOf(0.501, 0.809), "9" to doubleArrayOf(0.835, 0.809),
        "0" to doubleArrayOf(0.501, 0.898),
        "radera" to doubleArrayOf(0.167, 0.898),
        "identifiera" to doubleArrayOf(0.835, 0.898),
    )

    private const val SERVER_CMD =
        "setsid nohup sh -c 'CLASSPATH=/data/local/tmp/scrcpy-server " +
            "app_process / com.genymobile.scrcpy.Server 4.0 scid=-1 tunnel_forward=true " +
            "video=true audio=true control=true cleanup=false log_level=error " +
            "</dev/null >/sdcard/scrcpy-lan.log 2>&1 &'"

    private var screenW = 720
    private var screenH = 1600
    private var videoSocket: LocalSocket? = null
    private var audioSocket: LocalSocket? = null
    private var controlSocket: LocalSocket? = null
    private var controlOut: OutputStream? = null

    // ── shell через наш ADB-клиент ────────────────────────────────────────────

    /** Произвольная shell-команда (uid 2000). ok + stdout. */
    fun shell(command: String, timeoutMs: Long = 15000): Pair<Boolean, String> =
        AdbShell.exec(command, timeoutMs)

    /**
     * Открыть диплинк на телефоне. startActivity из фонового сервиса на Android 14+
     * блокируется (Background activity launch blocked), а shell-команда `am start`
     * запускает активити без ограничений BAL.
     */
    fun openDeeplink(url: String): Boolean {
        val safe = url.replace("'", "")
        val (ok, out) = shell("am start -a android.intent.action.VIEW -d '$safe'")
        val success = ok && !out.contains("Error")
        Log.i(TAG, "openDeeplink ok=$success ${out.take(120)}")
        return success
    }

    // ── Наблюдение за экраном (uiautomator/dumpsys через exec) ───────────────

    /** Тексты с экрана (даже с FLAG_SECURE-окнами — uiautomator их видит). */
    private fun texts(): List<String> {
        shell("uiautomator dump /data/local/tmp/ba-ui.xml", 25000)
        val (_, xml) = shell("cat /data/local/tmp/ba-ui.xml", 10000)
        return Regex("""text="([^"]+)"""").findAll(xml).map { it.groupValues[1] }
            .filter { it.isNotBlank() }.toList()
    }

    private fun waitText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (texts().any { it.contains(text, ignoreCase = true) }) return true
            Thread.sleep(800)
        }
        return false
    }

    /** Текущее foreground-окно (например, com.bankid.bus/...). */
    private fun focus(): String {
        val (_, out) = shell("dumpsys window | grep mCurrentFocus")
        return out
    }

    // ── Экран блокировки ──────────────────────────────────────────────────────

    private fun isLocked(): Boolean {
        val (_, out) = shell("dumpsys deviceidle | grep mScreenLocked")
        return out.contains("mScreenLocked=true")
    }

    /**
     * Снимаем локскрин, если он есть. Тачи/ввод идут обычным `input` (это системное
     * окно, не BankID — защищённого канала не нужно). null — разблокировано.
     */
    private fun unlockIfNeeded(lockPin: String): String? {
        if (!isLocked()) return null
        if (lockPin.isEmpty()) return "экран заблокирован, код не задан"
        Log.i(TAG, "locked — unlocking")
        // Свайп вверх: открыть поле ввода кода.
        shell("input swipe 360 1200 360 400 250")
        Thread.sleep(1000)
        shell("input text '${lockPin.replace("'", "")}'")
        Thread.sleep(500)
        shell("input keyevent 66") // ENTER
        Thread.sleep(1500)
        repeat(3) {
            if (!isLocked()) return null
            Thread.sleep(1000)
        }
        return "не удалось разблокировать экран"
    }

    // ── scrcpy-server lifecycle (спавним сами, подключаемся напрямую) ─────────

    private fun scrcpyPid(): Int? {
        val (_, out) = shell("ps -A -o PID,ARGS | grep 'com.genymobile.scrcpy.Server' | grep -v grep")
        return Regex("^\\s*(\\d+)", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toInt()
    }

    private fun connectChannel(): LocalSocket {
        val s = LocalSocket()
        s.connect(LocalSocketAddress("scrcpy", LocalSocketAddress.Namespace.ABSTRACT))
        return s
    }

    @Synchronized
    private fun ensureScrcpy() {
        // Всегда переспавниваем: socket order (video→audio→control) должен быть наш.
        scrcpyPid()?.let { pid ->
            shell("kill $pid")
            Thread.sleep(1000)
        }
        shell(SERVER_CMD)
        Thread.sleep(3000)

        val (_, sizeOut) = shell("wm size")
        Regex("(\\d+)x(\\d+)").find(sizeOut)?.let {
            screenW = it.groupValues[1].toInt(); screenH = it.groupValues[2].toInt()
        }

        // Порядок сокетов в tunnel_forward фиксирован: video → audio → control.
        var lastErr: Exception? = null
        repeat(20) {
            try {
                videoSocket = connectChannel()
                audioSocket = connectChannel()
                controlSocket = connectChannel()
                // Liveness-проба: живой сервер сразу шлёт метаданные в video-сокет.
                videoSocket!!.soTimeout = 4000
                val probe = ByteArray(256)
                val n = videoSocket!!.inputStream.read(probe)
                if (n <= 0) throw IllegalStateException("scrcpy video silent")
                videoSocket!!.soTimeout = 0
                controlOut = controlSocket!!.outputStream
                // Видео/аудио высасываем фоном — иначе буферы забьются и сервер встанет.
                listOfNotNull(videoSocket, audioSocket).forEach { sock ->
                    Thread({
                        val buf = ByteArray(64 * 1024)
                        runCatching {
                            while (sock.inputStream.read(buf) >= 0) { /* drain */ }
                        }
                    }, "bankid-drain").apply { isDaemon = true; start() }
                }
                Log.i(TAG, "scrcpy control ready ${screenW}x$screenH")
                return
            } catch (e: Exception) {
                lastErr = e
                closeSockets()
                Thread.sleep(1000)
            }
        }
        throw IllegalStateException("scrcpy connect failed: ${lastErr?.message}")
    }

    private fun closeSockets() {
        runCatching { controlSocket?.close() }
        runCatching { videoSocket?.close() }
        runCatching { audioSocket?.close() }
        controlSocket = null; videoSocket = null; audioSocket = null; controlOut = null
    }

    // ── Инъекция тачей по протоколу scrcpy ────────────────────────────────────

    private fun touchMsg(action: Int, x: Int, y: Int, pressure: Int, actionButton: Int, buttons: Int): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.put(2)                       // MSG_INJECT_TOUCH
        buf.put(action.toByte())
        buf.putLong(-1L)                 // POINTER_ID_MOUSE
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(screenW.toShort())
        buf.putShort(screenH.toShort())
        buf.putShort(pressure.toShort())
        buf.putInt(actionButton)
        buf.putInt(buttons)
        return buf.array()
    }

    private fun scrcpyTap(xPx: Int, yPx: Int) {
        val out = controlOut ?: throw IllegalStateException("control not connected")
        out.write(touchMsg(0, xPx, yPx, 0xffff.toInt(), 1, 1)) // ACTION_DOWN
        out.write(touchMsg(1, xPx, yPx, 0, 0, 0))              // ACTION_UP
        out.flush()
    }

    private fun key(name: String) {
        val (fx, fy) = KEYS.getValue(name)
        val jitter = { (Random.nextDouble() - 0.5) * 0.01 }    // человек не попадает в центр
        val x = ((fx + jitter()).coerceIn(0.01, 0.99) * screenW).toInt()
        val y = ((fy + jitter()).coerceIn(0.01, 0.99) * screenH).toInt()
        scrcpyTap(x, y)
        Log.i(TAG, "tap $name ($x,$y)")
    }

    private fun randomSleep(minMs: Long, maxMs: Long) =
        Thread.sleep((minMs + Random.nextLong(maxMs - minMs)))

    // ── Сценарий завершения ───────────────────────────────────────────────────

    /**
     * BankID уже открыт диплинком. Подтверждаем (если есть экран подтверждения),
     * вводим PIN, ждём закрытия BankID. Блокирующий — вызывать из фонового потока.
     * [lockPin] — код экрана блокировки устройства: headless-телефон стоит на пароле,
     * без разблокировки BankID не покажется. Пустой — экран не защищён.
     * Возвращает null при успехе, иначе текст ошибки.
     */
    fun complete(pinRaw: String, lockPin: String = ""): String? {
        val pin = pinRaw.filter { it.isDigit() }
        if (pin.isEmpty()) return "пустой PIN"
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            unlockIfNeeded(lockPin)?.let { return it }
            ensureScrcpy()
            Thread.sleep(1200)

            // Ждём любой экран со «säkerhetskod» (подтверждение или PIN-pad).
            if (!waitText("säkerhetskod", 10000)) return "экран BankID не найден"

            // Экран подтверждения («Identifiera/Signera med säkerhetskod») — кнопка на
            // месте «0» PIN-pad. Если уже PIN-pad («Ange säkerhetskod») — подтверждать нечего.
            val joined = texts().joinToString(" ")
            if ("med säkerhetskod" in joined && "Ange säkerhetskod" !in joined) {
                key("0")
                randomSleep(1800, 2600)
            }

            for (i in pin.indices) {
                key(pin[i].toString())
                if (i < pin.length - 1) randomSleep(700, 2200)
            }
            randomSleep(800, 2000)
            key("identifiera")

            // Ждём, пока BankID уйдёт с экрана (операция принята) или покажет ошибку.
            val deadline = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500)
                val tj = texts().joinToString(" ")
                if (Regex("avbruten|gick fel|inte längre").containsMatchIn(tj)) {
                    return "BankID отменил операцию"
                }
                val f = focus()
                if (f.isNotEmpty() && "com.bankid.bus" !in f) {
                    return null // BankID закрылся — подписано
                }
            }
            return "таймаут ожидания результата BankID"
        } catch (e: Exception) {
            Log.w(TAG, "complete failed", e)
            return e.message ?: "ошибка"
        } finally {
            closeSockets()
        }
    }

    private const val TAG = "PultBankId"
}
