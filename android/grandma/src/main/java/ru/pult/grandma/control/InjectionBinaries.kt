package ru.pult.grandma.control

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Self-provisioning бинарей инъекции в /data/local/tmp.
 *
 * lanagent.dex и scrcpy-inj.jar исторически пушились вручную при настройке
 * (adb push) — на чистых установках их нет, и вход BankID падает на
 * «scrcpy connect timeout» (поймано 2026-09-11). Оба бинаря вшиты в assets
 * (брал их из rescue/standhid — не зависят от рук и машин разработки):
 * недостающие копируем в filesDir и оттуда — shell'ом (uid 2000) в tmp.
 * /data/local/tmp переживает ребуты, поэтому ставим один раз.
 */
object InjectionBinaries {

    private const val TAG = "PultBin"
    private const val TMP = "/data/local/tmp"
    private val FILES = listOf("lanagent.dex", "scrcpy-inj.jar")

    /** true — оба бинаря гарантированно лежат в /data/local/tmp. Нужен живой shell. */
    fun ensure(context: Context): Boolean {
        val present = AdbShell.exec("ls $TMP/lanagent.dex $TMP/scrcpy-inj.jar 2>&1", 8_000)
        if (present.first && present.second.lines().count { it.startsWith(TMP) } == FILES.size) {
            return true
        }
        Log.i(TAG, "бинарей нет (${present.second.take(60).trim()}) — ставим из assets")
        for (name in FILES) {
            val local = File(context.filesDir, "bin/$name")
            if (!local.exists()) {
                val copied = runCatching {
                    local.parentFile?.mkdirs()
                    context.assets.open("binaries/$name").use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                if (!copied) {
                    Log.w(TAG, "asset $name не вытащился")
                    return false
                }
            }
            val (ok, out) = AdbShell.exec(
                "cp ${local.absolutePath} $TMP/$name && chmod 644 $TMP/$name && ls -la $TMP/$name",
                20_000,
            )
            Log.i(TAG, "install $name ok=$ok ${out.take(80).trim()}")
            if (!ok) return false
        }
        return true
    }
}
