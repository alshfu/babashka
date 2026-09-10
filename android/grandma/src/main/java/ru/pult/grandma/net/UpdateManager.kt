package ru.pult.grandma.net

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.pult.core.protocol.Signal
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.control.AdbShell
import ru.pult.grandma.updatable.ModuleRegistry
import ru.pult.grandma.updatable.PultModule
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Обновления приложения без переустановки из магазина: APK целиком и dex-модуль
 * (горячая замена логики BankID, см. [ModuleRegistry]).
 *
 * Два триггера: пуш `update-available` от сервера (мгновенно) и периодическая проверка
 * манифеста (UpdateCheckWorker, раз в 6 ч). Файл качается с http(s)-оригина сигналинга
 * с `?token=` (UPDATE_TOKEN зашит при сборке), проверяется по sha256 и только потом
 * применяется. Последняя обработанная версия запоминается — повторно не ставим.
 *
 * Любой сбой — только `update-status` с ошибкой: сервис и встроенное поведение
 * не страдают никогда.
 */
class UpdateManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Пуш от сервера. Возвращаемся сразу: сеть и установка — в фоновом потоке. */
    fun onPush(
        signal: Signal.UpdateAvailable,
        signalingUrl: String,
        send: (Signal.UpdateStatus) -> Unit,
    ) {
        Thread({
            runCatching {
                val attempt = applyUpdate(httpOrigin(signalingUrl), signal.kind, signal.version, signal.url, signal.sha256, signal.size)
                send(Signal.UpdateStatus(signal.kind, signal.version, attempt.ok, attempt.err))
            }.onFailure { e ->
                Log.w(TAG, "push update failed", e)
                runCatching { send(Signal.UpdateStatus(signal.kind, signal.version, false, (e.message ?: "error").take(160))) }
            }
        }, "pult-update").start()
    }

    /** Периодическая проверка (с потока WorkManager, блокирующая). */
    fun checkPeriodic(signalingUrl: String) {
        val origin = httpOrigin(signalingUrl)
        for (kind in listOf(KIND_APK, KIND_DEX)) {
            runCatching {
                val m = fetchManifest(origin, kind) ?: return@runCatching
                val attempt = applyUpdate(origin, m.kind, m.version, m.url, m.sha256, m.size)
                // Пропуск (уже стоит) молчим; реальные попытки — в отложенный статус:
                // у воркера нет живого сокета, сервис отдаст его при подключении.
                if (attempt.attempted) savePending(Signal.UpdateStatus(m.kind, m.version, attempt.ok, attempt.err))
            }.onFailure { Log.w(TAG, "periodic check $kind: ${it.message}") }
        }
    }

    /** Отложенный статус фоновой проверки (одноразовый: прочитал — забрал). */
    fun pendingStatus(): Signal.UpdateStatus? {
        val kind = prefs.getString(KEY_P_KIND, null) ?: return null
        val version = prefs.getString(KEY_P_VERSION, null) ?: return null
        val status = Signal.UpdateStatus(kind, version, prefs.getBoolean(KEY_P_OK, false), prefs.getString(KEY_P_ERR, null))
        prefs.edit()
            .remove(KEY_P_KIND).remove(KEY_P_VERSION).remove(KEY_P_OK).remove(KEY_P_ERR)
            .apply()
        return status
    }

    /** Поднять dex-модуль, применённый до перезапуска (файл + sha сверяются заново). */
    fun loadPersistedModule() {
        val name = prefs.getString(KEY_DEX_FILE, null) ?: return
        val sha = prefs.getString(KEY_DEX_SHA, null) ?: return
        val file = File(updatesDir(), name)
        if (!file.isFile) return
        if (!sha256(file).equals(sha, ignoreCase = true)) {
            file.delete()
            prefs.edit().remove(KEY_DEX_FILE).remove(KEY_DEX_SHA).apply()
            return
        }
        loadModule(file)?.let { Log.w(TAG, "persisted module not loaded: $it") }
    }

    // ── Внутренности ────────────────────────────────────────────────────────

    private class Attempt(val attempted: Boolean, val ok: Boolean, val err: String?)

    private class Manifest(val kind: String, val version: String, val url: String, val sha256: String, val size: Long)

    private fun applyUpdate(
        origin: String,
        kind: String,
        version: String,
        urlPath: String,
        sha256Expected: String,
        size: Long,
    ): Attempt {
        if (version == prefs.getString(knownKey(kind), null)) return Attempt(false, true, "already-installed")
        if (kind == KIND_APK && version == installedVersion()) {
            markKnown(kind, version)
            return Attempt(false, true, "already-installed")
        }

        val file = download(origin, urlPath, kind, version) ?: return Attempt(true, false, "download-failed")
        if (size > 0 && file.length() != size) {
            file.delete()
            return Attempt(true, false, "size-mismatch")
        }
        val sha = sha256(file)
        if (!sha.equals(sha256Expected, ignoreCase = true)) {
            file.delete()
            return Attempt(true, false, "sha256-mismatch")
        }
        Log.i(TAG, "загружено обновление $kind $version (${file.length()} байт), применяем")
        return when (kind) {
            KIND_DEX -> applyDex(file, version, sha)
            else -> applyApk(file, version)
        }
    }

    // ── APK ─────────────────────────────────────────────────────────────────

    private fun applyApk(file: File, version: String): Attempt {
        val silentErr = installSilent(file)
        if (silentErr == null) {
            markKnown(KIND_APK, version)
            return Attempt(true, true, null)
        }
        // Тихий путь недоступен (wireless debugging выключена и т.п.) — системный
        // установщик с подтверждением человеком/оператором. Повторно не донимаем.
        return if (openInstaller(file)) {
            markKnown(KIND_APK, version)
            Attempt(true, true, "manual-confirm")
        } else {
            Attempt(true, false, "install: $silentErr")
        }
    }

    /**
     * Тихая установка через shell (UID 2000): `pm install` доступен без диалогов.
     * Shell не читает приватную filesDir, поэтому файл кладётся во внешнюю папку
     * приложения; если и её не видно (sdcardfs на части прошивок) — копия в /data/local/tmp.
     * null — установлено; иначе текст ошибки.
     */
    private fun installSilent(file: File): String? {
        val dir = context.getExternalFilesDir("updates") ?: return "no-external-dir"
        dir.mkdirs()
        val shared = File(dir, file.name)
        runCatching {
            file.copyTo(shared, overwrite = true)
            shared.setReadable(true, false)
        }
        val (ok, out) = AdbShell.exec("pm install -r '${shared.absolutePath}'", PM_TIMEOUT_MS)
        if (ok && out.contains("Success", ignoreCase = true)) return null

        val tmp = "/data/local/tmp/${file.name}"
        val (cpOk, _) = AdbShell.exec("cp '${shared.absolutePath}' '$tmp' && chmod 644 '$tmp'", 30_000)
        if (cpOk) {
            val (ok2, out2) = AdbShell.exec("pm install -r '$tmp'; rm -f '$tmp'", PM_TIMEOUT_MS)
            if (ok2 && out2.contains("Success", ignoreCase = true)) return null
            return "pm: ${out2.take(100)}"
        }
        return "pm: ${out.take(100)}"
    }

    /** Ручной маршрут: системный установщик поверх FileProvider (подтверждает человек). */
    private fun openInstaller(file: File): Boolean = runCatching {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Разрешение на установку из этого источника выдаётся один раз, вручную.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    // ── dex ─────────────────────────────────────────────────────────────────

    private fun applyDex(file: File, version: String, sha: String): Attempt {
        val err = loadModule(file)
        return if (err == null) {
            markKnown(KIND_DEX, version)
            prefs.edit().putString(KEY_DEX_FILE, file.name).putString(KEY_DEX_SHA, sha).apply()
            Attempt(true, true, null)
        } else {
            // Плохой модуль не оставляем на диске: после перезапуска — встроенное поведение.
            file.delete()
            Attempt(true, false, err)
        }
    }

    /** null — модуль загружен и активирован; иначе текст ошибки (встроенное поведение сохранено). */
    private fun loadModule(file: File): String? = try {
        // targetSdk 36 (Android 14+): динамически загружаемый код обязан быть read-only.
        file.setWritable(false)
        file.setReadable(true, true)
        val loader = dalvik.system.DexClassLoader(
            file.absolutePath,
            context.codeCacheDir.absolutePath,
            null,
            javaClass.classLoader,
        )
        val impl = runCatching { loader.loadClass(MODULE_CLASS) }.getOrNull()
            ?: return "нет класса $MODULE_CLASS"
        val module = impl.getDeclaredConstructor().newInstance() as? PultModule
            ?: return "$MODULE_CLASS не реализует PultModule"
        ModuleRegistry.activate(module)
        Log.i(TAG, "dex-модуль активен: v${module.version()}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "dex load failed", e)
        (e.message ?: "dex-load-failed").take(160)
    }

    // ── Сеть ────────────────────────────────────────────────────────────────

    private fun fetchManifest(origin: String, kind: String): Manifest? {
        val url = "$origin/api/update/manifest?kind=$kind&token=${enc(BuildConfig.UPDATE_TOKEN)}"
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 404 || !resp.isSuccessful) return null
                val obj = Json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
                Manifest(
                    kind = obj["kind"]?.jsonPrimitive?.content ?: kind,
                    version = obj["version"]?.jsonPrimitive?.content ?: return null,
                    url = obj["url"]?.jsonPrimitive?.content ?: return null,
                    sha256 = obj["sha256"]?.jsonPrimitive?.content ?: return null,
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }
        }.onFailure { Log.w(TAG, "manifest $kind: ${it.message}") }.getOrNull()
    }

    private fun download(origin: String, urlPath: String, kind: String, version: String): File? {
        val sep = if ("?" in urlPath) "&" else "?"
        val url = origin + urlPath + sep + "token=" + enc(BuildConfig.UPDATE_TOKEN)
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                updatesDir().mkdirs()
                // Имя включает версию: новый файл не затирает ранее применённый.
                val name = "$kind-$version-" + urlPath.substringAfterLast('/').ifBlank { "update.bin" }.take(60)
                val file = File(updatesDir(), name)
                body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                file
            }
        }.onFailure { Log.w(TAG, "download $urlPath: ${it.message}") }.getOrNull()
    }

    // ── Мелочи ──────────────────────────────────────────────────────────────

    private fun updatesDir() = File(context.filesDir, "updates")

    private fun knownKey(kind: String) = "known_$kind"

    private fun markKnown(kind: String, version: String) {
        prefs.edit().putString(knownKey(kind), version).apply()
    }

    private fun savePending(status: Signal.UpdateStatus) {
        prefs.edit()
            .putString(KEY_P_KIND, status.kind)
            .putString(KEY_P_VERSION, status.version)
            .putBoolean(KEY_P_OK, status.ok)
            .putString(KEY_P_ERR, status.err)
            .apply()
    }

    private fun installedVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KIND_APK = "apk"
        const val KIND_DEX = "dex"

        /** Класс реализации модуля внутри dex-файла (безаргументный конструктор). */
        const val MODULE_CLASS = "ru.pult.grandma.dex.ModuleImpl"

        /** http(s)-оригин сигналинга: с него же раздаются /update и /api/update. */
        fun httpOrigin(wsUrl: String): String {
            var u = wsUrl.trim()
            u = when {
                u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
                u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
                else -> u
            }
            return u.removeSuffix("/ws").removeSuffix("/")
        }

        private const val TAG = "PultUpdate"
        private const val PREFS = "pult_updates"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val PM_TIMEOUT_MS = 120_000L
        private const val KEY_DEX_FILE = "dex_file"
        private const val KEY_DEX_SHA = "dex_sha"
        private const val KEY_P_KIND = "pending_kind"
        private const val KEY_P_VERSION = "pending_version"
        private const val KEY_P_OK = "pending_ok"
        private const val KEY_P_ERR = "pending_err"
    }
}
