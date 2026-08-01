package ru.pult.grandma.session

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Наблюдение за приложением на переднем плане, чтобы гасить показ на банковских
 * приложениях (docs/android-grandma.md §4).
 *
 * Через `UsageStatsManager`, а не через AccessibilityService: последний тянет за собой
 * ручную проверку Google и ломается в защищённом режиме Android 17 — а гашение нужно
 * уже в V1.
 *
 * Решение принимает устройство бабушки. Помощник отключить его не может — в протоколе
 * просто нет такого сообщения.
 */
class ForegroundAppWatcher(private val context: Context) {

    /**
     * Список пакетов. Обновляется вместе с приложением и дополняется вручную при настройке —
     * никаких «облачных списков»: это означало бы отправку того, чем пользуется бабушка,
     * на сервер.
     */
    private val bankingPackages: MutableSet<String> = mutableSetOf(
        "ru.sberbankmobile",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.tinkoff.sme",
        "com.idamob.tinkoff.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews",
        "ru.psbank.mobile",
        "ru.mkb.mobile",
        "com.openbank",
        "ru.rshb.mbank",
        "ru.sovcombank.halva",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
    )

    fun addBankingPackage(packageName: String) {
        bankingPackages += packageName
    }

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Пакет на переднем плане или null, если разрешения нет либо событий не было. */
    fun foregroundPackage(now: Long = System.currentTimeMillis()): String? {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usage.queryEvents(now - LOOKBACK_MS, now)
        val event = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                last = event.packageName
            }
        }
        return last
    }

    fun isBanking(packageName: String?): Boolean = packageName != null && packageName in bankingPackages

    /** Опрос во время сессии. Гашение банка за ~1 с достаточно, а частый опрос
     *  UsageStats на дешёвом MediaTek заметно грузит систему (источник лагов). */
    fun shouldRedact(now: Long = System.currentTimeMillis()): Boolean = isBanking(foregroundPackage(now))

    companion object {
        const val POLL_INTERVAL_MS = 1000L
        private const val LOOKBACK_MS = 10_000L
        const val REDACT_REASON = "banking-app"
    }
}
