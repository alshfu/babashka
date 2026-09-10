package ru.pult.grandma.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import ru.pult.grandma.PultApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сторож сервиса на AlarmManager (надёжнее WorkManager на агрессивных прошивках).
 *
 * Каждые несколько минут будит себя `setExactAndAllowWhileIdle` и поднимает сервис, если
 * тот умер. Плюс ведёт журнал «жив/умер»: на каких прошивках и через сколько сервис
 * убивают — это и есть диагностика из Этапа 1 плана.
 */
class ServiceHeartbeat : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Deaths.markAlive(context, "heartbeat")
        // Идемпотентность: живой сервис не перестартуем (лишние onStartCommand — лишние
        // перевзводы и шум в журнале), только перевзводим оба будильника. Мёртвый —
        // поднимаем, PultService.start сам проглатывает запрет FGS-старта из фона.
        if (!PultService.isRunning(context)) PultService.start(context)
        schedule(context) // перевзводим оба будильника
    }

    companion object {
        // Виден снаружи: PultService и crash-обработчик будят этот receiver своими
        // будильниками (task-removed, onDestroy, crash) — см. scheduleRestart/armCrashRestart.
        const val ACTION = "ru.pult.grandma.HEARTBEAT"
        private const val INTERVAL_MS = 5 * 60 * 1000L // 5 минут, exact
        private const val BACKUP_INTERVAL_MS = 15 * 60 * 1000L // резерв, inexact
        private const val REQ_EXACT = 0
        private const val REQ_BACKUP = 1

        fun schedule(context: Context) {
            scheduleExact(context)
            scheduleBackup(context)
        }

        private fun scheduleExact(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQ_EXACT,
                Intent(context, ServiceHeartbeat::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            // Точный будильник пробивает Doze, но на Android 14+ требует разрешения
            // USE_EXACT_ALARM/SCHEDULE_EXACT_ALARM. Если его нет — НЕ падаем, а ставим
            // неточный (для сторожа этого достаточно). Падение здесь роняло весь сервис.
            runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }.onFailure {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }

        /**
         * Второй, резервный будильник (inexact, 15 мин): exact-будильник после ребута или
         * глубокого Doze может не пережить перезагрузку планировщика — резервный с тем же
         * receiver'ом гарантирует, что сторож сработает хотя бы с задержкой. Оба перевзводятся
         * при каждом срабатывании и при каждом старте сервиса.
         */
        private fun scheduleBackup(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQ_BACKUP,
                Intent(context, ServiceHeartbeat::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = SystemClock.elapsedRealtime() + BACKUP_INTERVAL_MS
            runCatching {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }

        private const val REQ_CRASH = 4
        private const val CRASH_RESTART_MS = 5_000L

        /**
         * Будильник «подними сервис через 5 с» после непойманного исключения. Процесс
         * умирает — сторожа умирают вместе с ним, и без этого будильника сервис лежал бы
         * до следующего планового окна (до 15 мин). Срабатывание = обычный onReceive:
         * метка «жив», подъём сервиса, перевзвод обоих плановых будильников.
         */
        fun armCrashRestart(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQ_CRASH,
                Intent(context, ServiceHeartbeat::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = SystemClock.elapsedRealtime() + CRASH_RESTART_MS
            runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }.onFailure {
                runCatching {
                    alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                }
            }
        }
    }

    /**
     * Журнал жизни сервиса — для диагностики по устройствам. Пишем метку «жив» с временем
     * и причиной; по разрывам во времени видно, когда прошивка убивала сервис.
     */
    object Deaths {
        private const val TAG = "PultLifecycle"
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        fun markAlive(context: Context, reason: String) {
            append(context, "alive:$reason")
            PultApp.prefs(context).edit().putLong("last_alive", System.currentTimeMillis()).apply()
        }

        fun markDeath(context: Context, reason: String) {
            append(context, "death:$reason")
            Log.w(TAG, "service death: $reason")
        }

        /** Предупреждение самодиагностики — в тот же журнал, что и метки «жив/умер». */
        fun warn(context: Context, message: String) {
            append(context, "warn:$message")
            Log.w(TAG, "diag: $message")
        }

        /** Разрыв «был жив → следующая отметка сильно позже» = прошивка убила сервис. */
        fun lastGapMinutes(context: Context): Long {
            val last = PultApp.prefs(context).getLong("last_alive", 0L)
            if (last == 0L) return 0L
            return (System.currentTimeMillis() - last) / 60000
        }

        private val deviceTag =
            "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.VERSION.RELEASE}"

        private fun append(context: Context, event: String) {
            runCatching {
                val file = File(context.filesDir, "lifecycle.log")
                file.appendText("${fmt.format(Date())} $deviceTag $event\n")
                if (file.length() > 64 * 1024) file.writeText(file.readText().takeLast(32 * 1024))
            }
        }
    }
}
