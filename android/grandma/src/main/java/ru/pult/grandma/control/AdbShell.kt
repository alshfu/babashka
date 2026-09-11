package ru.pult.grandma.control

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Автономный shell-уровень устройства — свой ADB-клиент поверх wireless debugging.
 *
 * Вместо Shizuku/LanAgent: приложение само парится с локальным adbd по коду (один раз,
 * ключ хранится в приватной папке), а дальше выполняет shell-команды как UID 2000 через
 * TLS-подключение к 127.0.0.1. После ребута: приложение включает wireless debugging
 * (WRITE_SECURE_SETTINGS) и подключается само — компьютер не нужен вообще.
 */
object AdbShell {

    private const val TAG = "PultAdb"
    private const val KEY_FILE = "adb_key.pk8"
    private const val CERT_FILE = "adb_cert.der"

    private var privateKey: PrivateKey? = null
    private var certificate: Certificate? = null
    private var keyFile: File? = null
    private var certFile: File? = null
    private var appContext: Context? = null

    private val manager by lazy {
        object : AbsAdbConnectionManager() {
            // ВАЖНО: обращение через this@AdbShell — без квалификатора `privateKey`
            // резолвится в сам переопределённый геттер и уходит в бесконечную рекурсию.
            override fun getPrivateKey(): PrivateKey = this@AdbShell.privateKey!!
            override fun getCertificate(): Certificate = this@AdbShell.certificate!!
            override fun getDeviceName(): String = "pult"
        }.apply {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
        }
    }

    /** Ключ должен существовать ДО паринга — демон запоминает именно его. */
    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        if (privateKey != null) return
        keyFile = File(context.filesDir, KEY_FILE)
        certFile = File(context.filesDir, CERT_FILE)
        runCatching {
            Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }
        if (keyFile!!.exists() && certFile!!.exists()) {
            runCatching {
                val keySpec = PKCS8EncodedKeySpec(keyFile!!.readBytes())
                privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile!!.inputStream())
            }
        }
        if (privateKey == null) generateAndStore()
    }

    private fun generateAndStore() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        privateKey = kp.private
        certificate = makeCert(kp)
        runCatching {
            keyFile!!.writeBytes(kp.private.encoded)
            certFile!!.writeBytes(certificate!!.encoded)
        }
        Log.i(TAG, "adb keypair generated")
    }

    /** Самоподписанный X.509 — формальность протокола ADB (подпись AUTH). */
    private fun makeCert(kp: java.security.KeyPair): Certificate {
        val now = System.currentTimeMillis()
        val x509 = android.sun.security.x509.X509CertInfo().apply {
            set("version", android.sun.security.x509.CertificateVersion(2))
            set("serialNumber", android.sun.security.x509.CertificateSerialNumber(BigInteger(64, SecureRandom())))
            set("algorithmID", android.sun.security.x509.CertificateAlgorithmId(android.sun.security.x509.AlgorithmId.get("SHA256withRSA")))
            val name = android.sun.security.x509.X500Name("CN=pult")
            set("subject", android.sun.security.x509.CertificateSubjectName(name))
            set("issuer", android.sun.security.x509.CertificateIssuerName(name))
            set("key", android.sun.security.x509.CertificateX509Key(kp.public))
            set("validity", android.sun.security.x509.CertificateValidity(Date(now - 1000), Date(now + 10L * 365 * 86400000)))
        }
        return android.sun.security.x509.X509CertImpl(x509).also { it.sign(kp.private, "SHA256withRSA") }
    }

    /**
     * Разовый паринг: код и порт из системного диалога беспроводной отладки.
     * После успеха наш ключ авторизован у adbd навсегда (переживает ребуты и разряд).
     */
    @Synchronized
    fun pair(port: Int, code: String): Boolean {
        return runCatching {
            manager.pair("127.0.0.1", port, code)
        }.onFailure { Log.w(TAG, "pair failed", it) }
            .getOrDefault(false)
    }

    /**
     * Авто-паринг без ручного ввода: своей accessibility-службой открываем настройки
     * разработчика, листаем до «беспроводной отладки», открываем диалог сопряжения,
     * читаем 6-значный код и порт с экрана и паримся. Работает на любой локали
     * (списки строк на шведском/английском/русском). Требование: служба включена —
     * она и так нужна остальному стеку управления.
     */
    fun autoPair(context: Context): Pair<Boolean, String> {
        val svc = RemoteControlService.instance ?: return false to "accessibility service off"
        // Wireless debugging должна быть включена — иначе строки сопряжения серые.
        // Через Settings (WRITE_SECURE_SETTINGS) и, если не сработало, тумблером на экране.
        runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }
        runCatching {
            context.startActivity(
                Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { return false to "cannot open dev settings: ${it.message}" }
        Thread.sleep(1500)

        // Строка беспроводной отладки: левая часть открывает страницу (важно —
        // правый тумблер лишь включает, нам нужна страница).
        val wirelessRow = listOf("wireless debugging", "trådlös felsökning", "беспроводная отладка")
        if (!findAndTap(context, wirelessRow, tapLeft = true)) return false to "wireless row not found"
        Thread.sleep(1500)

        // Если wireless выключена (серые строки и подсказка «Aktivera…») — щёлкаем тумблер.
        if (isWirelessOff()) {
            toggleWireless(context)
            Thread.sleep(1500)
        }

        // «Сопряжение по коду»: центр строки.
        val pairRow = listOf("pairing code", "parkopplingskod", "коду сопряжения", "кодом сопряжения", "pair device")
        if (!findAndTap(context, pairRow, tapLeft = false)) return false to "pair row not found"
        Thread.sleep(1200)

        // Читаем код и порт с экрана диалога.
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            val tree = RemoteControlService.dumpTreeJson() ?: break
            val code = Regex("\"text\":\"(\\d{6})\"").find(tree)?.groupValues?.get(1)
            val port = Regex("\"text\":\"[\\d.]+:(\\d+)\"").find(tree)?.groupValues?.get(1)?.toIntOrNull()
            if (code != null && port != null) {
                val ok = pair(port, code)
                Log.i(TAG, "autoPair: $ok (port=$port)")
                return ok to if (ok) "paired" else "pair handshake failed"
            }
            Thread.sleep(800)
        }
        return false to "code/port not found on screen"
    }

    /** Признак выключенной wireless: на странице подсказка «включите, чтобы видеть устройства». */
    private fun isWirelessOff(): Boolean {
        val tree = RemoteControlService.dumpTreeJson() ?: return false
        return tree.contains("Aktivera trådlös felsökning") ||
            tree.contains("turn on wireless debugging", ignoreCase = true) ||
            tree.contains("включите беспроводную отладку", ignoreCase = true)
    }

    /** Тап по тумблеру wireless debugging (правый верхний ряд страницы). */
    private fun toggleWireless(context: Context) {
        val svc = RemoteControlService.instance ?: return
        val dm = context.resources.displayMetrics
        // Тумблер первой строки: ищем текст строки, бьём по правой части той же высоты.
        val tree = RemoteControlService.dumpTreeJson()
        val m = tree?.let {
            Regex("\"text\":\"([^\"]*${Regex.escape("Felsökningsläge")}[^\"]*)\"[^}]*\"bounds\":\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]")
                .find(it)
        }
        val tapY = m?.let { ((it.groupValues[3].toInt() + it.groupValues[5].toInt()) / 2).toFloat() }
            ?: (dm.heightPixels * 0.21f)
        Log.i(TAG, "toggleWireless tap at y=$tapY")
        svc.tap(dm.widthPixels * 0.85f, tapY)
    }

    /** Ищем строку по тексту (скроллим список) и тапаем её. tapLeft — тап по левой части. */
    private fun findAndTap(context: Context, texts: List<String>, tapLeft: Boolean): Boolean {
        val svc = RemoteControlService.instance ?: return false
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        // Сначала наверх: список мог остаться проскролленным к низу с прошлого захода.
        // Скролл вверх большими шагами (действием доступности).
        repeat(4) {
            RemoteControlService.scrollList(forward = false)
            Thread.sleep(300)
        }
        // Вниз — МЕЛКИМИ шагами медленным драгом: одно ACTION_SCROLL перепрыгивает
        // полтора экрана и целевая строка проскакивается между дампами (проверено).
        repeat(14) { attempt ->
            val tree = RemoteControlService.dumpTreeJson()
            if (attempt >= 0) {
                val sample = tree?.let { t ->
                    Regex("\"text\":\"([^\"]*)\"").findAll(t).map { it.groupValues[1] }
                        .filter { it.isNotBlank() }.take(8).joinToString(" | ")
                }
                Log.i(TAG, "findAndTap[$attempt]: $sample")
            }
            if (tree != null) {
                for (t in texts) {
                    val m = Regex(
                        "\"text\":\"([^\"]*${Regex.escape(t)}[^\"]*)\"[^}]*\"bounds\":\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]",
                        RegexOption.IGNORE_CASE,
                    ).find(tree)
                    if (m != null) {
                        val y1 = m.groupValues[3].toInt()
                        val y2 = m.groupValues[5].toInt()
                        // Двойной тап: первый гасит инерцию списка после драга (система
                        // ест его как стоп-скролл), второй уже кликает по строке.
                        val tapX = if (tapLeft) dm.widthPixels * 0.3f else cx
                        val tapY = ((y1 + y2) / 2).toFloat()
                        svc.tap(tapX, tapY)
                        Thread.sleep(500)
                        svc.tap(tapX, tapY)
                        return true
                    }
                }
            }
            svc.swipe(cx, dm.heightPixels * 0.6f, cx, dm.heightPixels * 0.45f, 800)
            Thread.sleep(800)
        }
        return false
    }

    @Synchronized
    private fun ensureConnected(): Boolean {
        if (manager.isConnected) return true
        val ctx = appContext ?: return false
        // Быстрый путь: прямой TLS-коннект на запомненный порт wireless adb.
        // mDNS-автопоиск на части сетей даёт 60–90 с холодного старта — за это время
        // autostarttoken BankID протухает и вход срывается.
        val port = ctx.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)
            .getInt("adb_wifi_port", 0)
        if (port > 0) {
            val ok = runCatching { manager.connectTls(ctx, port.toLong()) }
                .onFailure { Log.w(TAG, "fast connect :$port failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) return true
        }
        return runCatching { manager.autoConnect(ctx, 10_000) }
            .onFailure { Log.w(TAG, "connect failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Включить беспроводную отладку настройкой (WRITE_SECURE_SETTINGS выдан при
     * настройке устройства). После ребута прошивка её сбрасывает — PultService
     * включает при старте, это — дублирующий путь для реанимации (Reanimate),
     * когда shell нужен, а adbd ещё спит.
     */
    fun enableWirelessDebugging() {
        val ctx = appContext ?: return
        runCatching {
            android.provider.Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
        }.onFailure { Log.w(TAG, "enableWirelessDebugging: ${it.message}") }
    }

    /**
     * «Живая сессия управления»: LanAgent (TCP loopback, переживает выключение
     * wireless debugging) ИЛИ установленная adbd-сессия. Диплинк-путь BankID
     * смотрит только сюда: подъём adbd-подключения включил бы «Trådlös
     * felsökning», и BankID отказался бы работать. LanAgent к нему невидим.
     */
    fun hasLiveSession(): Boolean =
        LanShell.available() || hasAdbSession()

    /** Только adbd-сессия (без LanAgent): живая adbd = wireless debugging активна —
     *  BankID её видит и блокирует. Используется при гашении adb_wifi перед подъёмом. */
    fun hasAdbSession(): Boolean = runCatching { manager.isConnected }.getOrDefault(false)

    /** Канал к localabstract-сокету устройства через нашу adbd-сессию. Прямой
     *  LocalSocket к сокетам shell приложению запрещён на HyperOS/Android 15
     *  (ECONNREFUSED без avc), а прокси через adbd — разрешён. */
    fun openLocalAbstract(name: String): io.github.muntashirakon.adb.AdbStream? {
        if (!ensureConnected()) return null
        return runCatching { manager.openStream("localabstract:$name") }
            .onFailure { Log.w(TAG, "openLocalAbstract $name: ${it.message}") }
            .getOrNull()
    }

    /** Выполнить shell-команду как shell (UID 2000). ok + stdout.
     *  Сначала LanAgent (TCP loopback, не будит wireless debugging), потом adbd. */
    fun exec(cmd: String, timeoutMs: Long = 15000): Pair<Boolean, String> {
        if (LanShell.available()) {
            val res = LanShell.exec(cmd, timeoutMs)
            if (res.first) return res
            Log.w(TAG, "lanagent exec failed, fallback adbd: ${res.second.take(80)}")
        }
        if (!ensureConnected()) return false to "adb not connected (pairing needed?)"
        return runCatching {
            val stream = manager.openStream("shell:$cmd")
            val out = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    stream.openInputStream().bufferedReader().forEachLine { out.append(it).append('\n') }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()
            readerThread.join(timeoutMs)
            runCatching { stream.close() }
            if (readerThread.isAlive) false to "timeout" else true to out.toString().trim()
        }.getOrElse {
            false to (it.message ?: "exec failed")
        }
    }
}
