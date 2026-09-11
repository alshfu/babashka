package ru.pult.grandma.control

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import ru.pult.grandma.ui.UnlockActivity
import java.io.File

/**
 * Снятие блокировки экрана без рук — по запросу на старт трансляции/управления.
 *
 * Телефон далеко: экран может быть погашен и заперт, а трансляция чёрного локскрина
 * бесполезна. Порядок: вейклок с подъёмом подсветки → прозрачная активность поверх
 * keyguard → свайп вверх (свайп-лок или подъём PIN-пада) → цифры PIN-пада через
 * дерево доступности, если PIN сохранён (файл files/lock_pin — задаётся один раз с
 * машины разработки: `adb shell run-as se.pult.app sh -c 'echo 1234 > files/lock_pin'`).
 *
 * Банковский контур не трогаем: это путь вне BankIdAgent; PIN Банка и PIN локскрина —
 * разные вещи в разных хранилищах.
 */
object ScreenUnlock {

    private const val TAG = "PultUnlock"
    private const val PIN_FILE = "lock_pin"

    fun lockPin(context: Context): String? =
        runCatching { File(context.filesDir, PIN_FILE).readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= 16 && it.all(Char::isDigit) }

    /** Разбудить и отпереть. Всё best-effort: чего-то нет (a11y выключена и т.п.) — делаем остальное. */
    fun unlock(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            runCatching {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "pult:unlock",
                ).acquire(60_000L) // сам отпустится; трансляция дальше держит подсветку активностью
            }.onFailure { Log.w(TAG, "wakelock: ${it.message}") }
        }
        // Keyguard: прозрачная активность поднимает экран поверх локскрина.
        runCatching {
            context.startActivity(
                Intent(context, UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "unlock activity: ${it.message}") }

        Thread({
            Thread.sleep(1_500)
            val svc = RemoteControlService.instance
            if (svc == null) {
                Log.w(TAG, "a11y off — свайп/PIN пропускаем")
                return@Thread
            }
            val dm = context.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            // Свайп вверх: свайп-лок снимается, PIN-пад поднимается.
            svc.swipe(cx, dm.heightPixels * 0.85f, cx, dm.heightPixels * 0.25f, 350)
            val pin = lockPin(context)
            if (pin != null) {
                Thread.sleep(1_200)
                enterPin(svc, pin)
            }
        }, "pult-unlock").apply { isDaemon = true; start() }
    }

    /**
     * Цифры PIN-пада тапаем по дереву доступности (позиции клавиш прошивки плавают,
     * а ноды с цифрами находятся всегда). Несколько попыток: пад поднимается не мгновенно.
     */
    private fun enterPin(svc: RemoteControlService, pin: String) {
        val digitRegex = Regex("\\\"(?:text|desc)\\\":\\\"([0-9])\\\"[^}]*\\\"bounds\\\":\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]")
        repeat(4) { attempt ->
            val tree = RemoteControlService.dumpTreeJson()
            if (tree == null) {
                Log.w(TAG, "dumpTree failed")
                return
            }
            val pads = digitRegex.findAll(tree)
                .map { m ->
                    m.groupValues[1][0] to floatArrayOf(
                        (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2f,
                        (m.groupValues[3].toInt() + m.groupValues[5].toInt()) / 2f,
                    )
                }
                .toMap()
            if (pin.all { pads.containsKey(it) }) {
                Log.i(TAG, "PIN-pad найден (${pads.size} клавиш), вводим")
                for (d in pin) {
                    val pt = pads.getValue(d)
                    svc.tap(pt[0], pt[1])
                    Thread.sleep(280)
                }
                return
            }
            Log.w(TAG, "PIN-pad не собран (попытка ${attempt + 1}), клавиш: ${pads.size}")
            Thread.sleep(900)
        }
    }
}
