package ru.pult.grandma.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.pult.grandma.control.RemoteControlService

/**
 * Дамп дерева доступности в файл — ТОЛЬКО в отладочной сборке.
 *
 * Нужен, чтобы снять экраны защищённых приложений (BankID) для сценариев:
 * uiautomator и screencap блокируются FLAG_SECURE, а служба доступности видит
 * полное дерево элементов с координатами.
 *
 *   adb shell am broadcast -a ru.pult.grandma.DEBUG_DUMP \
 *     -n se.pult.app/.debug.DebugDumpReceiver --es path "/data/local/tmp/tree.txt"
 */
class DebugDumpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // /data/local/tmp для приложения недоступен — пишем в свои файлы.
        val path = intent.getStringExtra("path")
            ?: java.io.File(context.getExternalFilesDir(null), "a11y_tree.txt").absolutePath
        val ok = RemoteControlService.dumpTree(path)
        Log.i(TAG, "dump to $path: ${if (ok) "ok" else "failed (service off or no window)"}")
    }

    private companion object {
        const val TAG = "PultDebugDump"
    }
}
