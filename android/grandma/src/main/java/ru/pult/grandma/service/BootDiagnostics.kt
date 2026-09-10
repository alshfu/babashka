package ru.pult.grandma.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import ru.pult.grandma.PultApp

/**
 * Самодиагностика фоновой жизни при старте сервиса.
 *
 * Прошивки (MIUI/Huawei) убивают фон за отсутствие любого из разрешений ниже, при этом
 * сами ничего не спрашивают — молчаливая смерть. Проверяем ключевые пермишены и пишем
 * предупреждения в тот же lifecycle-журнал, что и ServiceHeartbeat (по разрывам видно,
 * что именно сломалось на конкретном устройстве).
 */
object BootDiagnostics {
    private const val TAG = "PultDiagnostics"
    private const val PREF_LAST_BATTERY_PROMPT = "diag_battery_prompt_at"
    private const val BATTERY_PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L

    // Полное имя компонента a11y-службы — как она видна в ENABLED_ACCESSIBILITY_SERVICES.
    private const val A11Y_SERVICE = "se.pult.app/ru.pult.grandma.control.RemoteControlService"

    fun run(context: Context) {
        val app = context.applicationContext
        runCatching { checkBatteryOptimizations(app) }
        runCatching { checkNotifications(app) }
        runCatching { checkOverlay(app) }
        runCatching { checkAccessibility(app) }
    }

    private fun checkBatteryOptimizations(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        ServiceHeartbeat.Deaths.warn(context, "battery-optimizations: not ignored")
        val prefs = PultApp.prefs(context)
        val last = prefs.getLong(PREF_LAST_BATTERY_PROMPT, 0L)
        if (System.currentTimeMillis() - last < BATTERY_PROMPT_INTERVAL_MS) return
        // Системный диалог «не оптимизировать» запускаем с application-контекста без
        // FLAG_ACTIVITY_NEW_TASK: на API 31+ старт activity из фона запрещён (BAL), но
        // держатель SYSTEM_ALERT_WINDOW освобождён — часть прошивок покажет диалог.
        // Где запрещено — бросит SecurityException/BackgroundActivityStartException:
        // это норма, ловим и остаёмся на записи в журнале.
        val started = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }.onFailure {
            Log.w(TAG, "battery prompt not allowed: ${it.message}")
        }.isSuccess
        if (started) prefs.edit().putLong(PREF_LAST_BATTERY_PROMPT, System.currentTimeMillis()).apply()
    }

    private fun checkNotifications(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) {
            ServiceHeartbeat.Deaths.warn(context, "notifications: disabled (FGS invisible)")
        }
    }

    private fun checkOverlay(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            ServiceHeartbeat.Deaths.warn(context, "overlay: canDrawOverlays=false")
        }
    }

    private fun checkAccessibility(context: Context) {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return ServiceHeartbeat.Deaths.warn(context, "accessibility: RemoteControlService off")
        val found = enabled.split(':').any { it.equals(A11Y_SERVICE, ignoreCase = true) }
        if (!found) ServiceHeartbeat.Deaths.warn(context, "accessibility: RemoteControlService off")
    }
}
