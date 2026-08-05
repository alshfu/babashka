package ru.pult.grandma.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Паринг wireless ADB без ухода с системного диалога: диалог должен оставаться
 * на переднем плане (иначе adbd глушит рекламу), поэтому код принимаем бродкастом,
 * а паримся в фоне:
 *   am broadcast -a se.pult.app.PAIR --ei port 45119 --es code 018974
 * Результат — в лог (PultAdb) и в prefs (pair_ok_at для диагностики).
 */
class PairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val port = intent.getIntExtra("port", 0)
        val code = intent.getStringExtra("code")?.filter(Char::isDigit).orEmpty()
        if (port > 0 && code.length != 6) {
            Log.w(TAG, "bad pair args: port=$port code=$code")
            return
        }
        AdbShell.init(context)
        // Процесс переживает только живые компоненты: паринг (TLS-рукопожатие)
        // может занять больше бродкаст-окна — отдаём работу foreground-сервису.
        // Без аргументов сервис запустит авто-паринг (чтение кода своей a11y-службой).
        val work = Intent(context, ru.pult.grandma.service.PultService::class.java)
            .setAction(ru.pult.grandma.service.PultService.ACTION_PAIR_ADB)
            .putExtra("port", port)
            .putExtra("code", code)
        context.startService(work)
        Log.i(TAG, "pair requested (port=$port)")
    }

    private companion object {
        const val ACTION = "se.pult.app.PAIR"
        const val TAG = "PultAdb"
    }
}
