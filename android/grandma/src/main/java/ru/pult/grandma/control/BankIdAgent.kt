package ru.pult.grandma.control

import android.util.Log
import java.net.Socket
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

    // Без nohup: под adbd-pty nohup пытается создать nohup.out в / (read-only) и умирает,
    // не запустив сервер. setsid уже отцепляет процесс от сессии — этого достаточно.
    private const val SERVER_CMD =
        "setsid sh -c 'CLASSPATH=/data/local/tmp/scrcpy-inj.jar " +
            "app_process / com.genymobile.scrcpy.Server 4.1 scid=-1 tunnel_forward=true " +
            "video=true audio=true control=true cleanup=false log_level=error " +
            "</dev/null >/sdcard/scrcpy-lan.log 2>&1 &'"

    private var screenW = 720
    private var screenH = 1600
    // Каналы к scrcpy-server идут через adbd (localabstract), а не напрямую через
    // LocalSocket: приложению на HyperOS/Android 15 прямой коннект к сокетам shell
    // запрещён (ECONNREFUSED). Пока держим каналы открытыми, жива и adbd-сессия,
    // а значит и заспавненный через неё сервер.
    private var videoChannel: Socket? = null
    private var audioChannel: Socket? = null
    private var controlChannel: Socket? = null
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
        Log.i(TAG, "openDeeplink enter at ${System.currentTimeMillis()}")
        val safe = url.replace("'", "")
        val (ok, out) = shell("am start -a android.intent.action.VIEW -d '$safe'")
        val success = ok && !out.contains("Error")
        Log.i(TAG, "openDeeplink ok=$success ${out.take(120)}")
        return success
    }

    // ── Наблюдение за экраном (uiautomator/dumpsys через exec) ───────────────

    /** Тексты с экрана (даже с FLAG_SECURE-окнами — uiautomator их видит).
     *  Фильтрация НА УСТРОЙСТВЕ: LanAgent режет вывод до 2000 символов, а
     *  полный дамп десятки КБ — cat целиком терял все тексты (xmlLen=2000,
     *  nodes=0, «экран BankID не найден» 09-09). */
    private fun texts(): List<String> {
        val (_, xml) = shell(
            "uiautomator dump /data/local/tmp/ba-ui.xml >/dev/null; " +
                "grep -oE 'text=\"[^\"]*\"' /data/local/tmp/ba-ui.xml",
            25000,
        )
        val out = Regex("""text="([^"]+)"""").findAll(xml).map { it.groupValues[1] }
            .filter { it.isNotBlank() }.toList()
        Log.i(TAG, "texts: xmlLen=${xml.length} nodes=${out.size}")
        return out
    }

    private fun waitText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var probe = 0
        while (System.currentTimeMillis() < deadline) {
            val t = texts()
            probe++
            // Диагностика пустых дампов: без неё «экран не найден» неотличим от слома канала.
            if (probe <= 2 || probe % 5 == 0) {
                Log.i(TAG, "waitText[$probe] nodes=${t.size} first=${t.take(3).joinToString("|").take(80)}")
            }
            if (t.any { it.contains(text, ignoreCase = true) }) return true
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
        // mScreenLocked есть не на всех прошивках (на HyperOS его нет — было ложное
        // «разблокировано», автономный вход умирал под локскрином). Кросс-проверка.
        val (_, a) = shell("dumpsys deviceidle | grep mScreenLocked")
        if (a.contains("mScreenLocked=true")) return true
        val (_, b) = shell("dumpsys trust | grep deviceLocked")
        if (b.contains("deviceLocked=1")) return true
        val (_, c) = shell("dumpsys window | grep mDreamingLockscreen")
        return c.contains("mDreamingLockscreen=true")
    }

    /**
     * Снимаем локскрин, если он есть. Тачи/ввод идут обычным `input` (это системное
     * окно, не BankID — защищённого канала не нужно). null — разблокировано.
     */
    private fun unlockIfNeeded(lockPin: String): String? {
        if (!isLocked()) return null
        if (lockPin.isEmpty()) return "экран заблокирован, код не задан"
        Log.i(TAG, "locked — unlocking")
        // Свайп вверх: открыть поле ввода кода. Ввод цифрами keyevent'ами —
        // `input text` на локскрине MIUI требует фокуса поля, которого нет.
        shell("input swipe 360 1200 360 400 250")
        Thread.sleep(1000)
        for (ch in lockPin) {
            shell("input keyevent KEYCODE_" + ch)
            Thread.sleep(400)
        }
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

    // Каналы scrcpy-server через LanAgent-релей (TCP 47202 → localabstract):
    // в отличие от adbd-канала, переживает выключение wireless debugging —
    // BankID требует её выключенной на всём протяжении входа.
    private fun connectChannel(): Socket =
        LanShell.openScrcpyChannel()
            ?: throw IllegalStateException("lanagent relay unavailable")

    /**
     * Подъём/переиспользование scrcpy-server с жёстким бюджетом времени. Без него
     * недоступный adbd держал монитор десятки минут (наблюдалось 2561 с) и убивал
     * вход BankID. Монитор держит только рабочий поток — вызывающий ждёт join'ом
     * и по таймауту падает сразу.
     */
    private fun ensureScrcpy() {
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val worker = Thread({
            try {
                ensureScrcpyBlocking()
            } catch (e: Exception) {
                failure.set(e)
            }
        }, "bankid-scrcpy")
        worker.isDaemon = true
        worker.start()
        worker.join(ENSURE_SCRCPY_TIMEOUT_MS)
        if (worker.isAlive) {
            // Застрявший коннект: рвём каналы (разблокирует чтения потока) и падаем.
            closeSockets()
            throw IllegalStateException("scrcpy connect timeout ${ENSURE_SCRCPY_TIMEOUT_MS / 1000}s")
        }
        failure.get()?.let { throw it }
    }

    @Synchronized
    private fun ensureScrcpyBlocking() {
        // Живой сервер уже слушает abstract-сокет? Переиспользуем его: сервер,
        // заспавненный через нашу wireless-adb сессию, adbd убивает вместе с сессией,
        // поэтому долгоживущий внешний сервер (usb-adb) надёжнее собственного спавна.
        if (runCatching { tryConnect() }.getOrDefault(false)) return

        // Своя попытка: переспавниваем — socket order (video→audio→control) должен быть наш.
        scrcpyPid()?.let { pid ->
            shell("kill $pid")
            Thread.sleep(1000)
        }
        val (spawnOk, spawnOut) = shell(SERVER_CMD, 30000)
        Log.i(TAG, "spawn ok=$spawnOk out=${spawnOut.take(200)}")
        Thread.sleep(3000)

        var lastErr: Exception? = null
        repeat(20) {
            try {
                if (tryConnect()) return
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(1000)
            }
        }
        throw IllegalStateException("scrcpy connect failed: ${lastErr?.message}")
    }

    /** Подключение video→audio→control + liveness-проба. true, если сервер жив и наш. */
    private fun tryConnect(): Boolean {
        val (_, sizeOut) = shell("wm size")
        Regex("(\\d+)x(\\d+)").find(sizeOut)?.let {
            screenW = it.groupValues[1].toInt(); screenH = it.groupValues[2].toInt()
        }
        try {
            videoChannel = connectChannel()
            audioChannel = connectChannel()
            controlChannel = connectChannel()
            // Liveness-проба: живой сервер сразу шлёт метаданные в video-канал.
            // Таймаута на чтении у adbd-потока нет — оборачиваем в поток с join.
            val input = videoChannel!!.getInputStream()
            val probeOk = java.util.concurrent.atomic.AtomicBoolean(false)
            val probeThread = Thread({
                runCatching { if (input.read(ByteArray(256)) > 0) probeOk.set(true) }
            }, "bankid-probe")
            probeThread.isDaemon = true
            probeThread.start()
            probeThread.join(4000)
            if (!probeOk.get()) throw IllegalStateException("scrcpy video silent")
            controlOut = controlChannel!!.getOutputStream()
            // Видео/аудио высасываем фоном — иначе буферы забьются и сервер встанет.
            listOfNotNull(videoChannel, audioChannel).forEach { ch ->
                Thread({
                    val buf = ByteArray(64 * 1024)
                    runCatching {
                        val stream = ch.getInputStream()
                        while (stream.read(buf) >= 0) { /* drain */ }
                    }
                }, "bankid-drain").apply { isDaemon = true; start() }
            }
            Log.i(TAG, "scrcpy control ready ${screenW}x$screenH")
            return true
        } catch (e: Exception) {
            closeSockets()
            throw e
        }
    }

    private fun closeSockets() {
        runCatching { controlChannel?.close() }
        runCatching { videoChannel?.close() }
        runCatching { audioChannel?.close() }
        controlChannel = null; videoChannel = null; audioChannel = null; controlOut = null
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
        // Местный вход (бабушка сама открыла BankID): автоматика не вмешивается —
        // ни PIN, ни тапов. Только окно после удалённого диплинка (BankIdMode).
        if (!BankIdMode.isRemote()) {
            Log.i(TAG, "LOCAL-режим: автономные действия BankID запрещены")
            return "bankid-local-mode"
        }
        val pin = pinRaw.filter { it.isDigit() }
        if (pin.isEmpty()) return "пустой PIN"
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            unlockIfNeeded(lockPin)?.let { return it }
            ensureScrcpy()
            Thread.sleep(1200)

            // Ждём любой экран со «säkerhetskod» (подтверждение или PIN-pad).
            // Первый uiautomator dump в свежей adbd-сессии инициализируется долго
            // (несколько секунд) — 10 с не хватало, сценарий падал зря.
            if (!waitText("säkerhetskod", 30000)) return "экран BankID не найден"

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

    /** Жёсткий бюджет на подъём scrcpy: зависший коннект не должен блокировать вход. */
    private const val ENSURE_SCRCPY_TIMEOUT_MS = 15_000L
}
