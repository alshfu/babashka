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
        // Шведские банки и BankID — телефон в Швеции, §5.3 ТЗ.
        "se.nordea.mobilebank",
        "se.swedbank.mobilbank",
        "se.swedbank.mobil",
        "com.klarna.mobile",
        "se.seb.android",
        BANKID_PACKAGE,
        "se.sparbanken.mobilbank",
        "se.skandiabanken.app",
        "se.icabanken.app",
        "se.lansforsakringar.bank",
        "se.handelsbanken.mobilbank",
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
        if (last != null) return last
        // Fallback: на некоторых прошивках (MIUI) queryEvents не обновляется,
        // а queryUsageStats возвращает последнее использованное приложение.
        return usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - STATS_LOOKBACK_MS, now)
            .filter { it.lastTimeUsed in (now - STATS_LOOKBACK_MS)..now }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun isBanking(packageName: String?): Boolean = packageName != null && packageName in bankingPackages

    /** Пакеты, экран которых действительно гасим: только те, что уходят в FLAG_SECURE.
     *  Swedbank оставляем видимым — на нём надо нажать «Logga in», иначе BankID не откроется.
     *  BankID гасим: именно его экран нельзя показывать и именно туда пишем PIN. */
    private val redactPackages: MutableSet<String> = mutableSetOf(
        BANKID_PACKAGE,
    )

    fun shouldRedactPackage(packageName: String?): Boolean = packageName != null && packageName in redactPackages

    // Гистерезис: UsageStats на MIUI мигает (банк «появляется» и «исчезает» между опросами).
    // Гасим показ только после N подряд срабатываний.
    // Липкость: раз погасили — держим, пока банк реально не закрыт (долгий «чистый» период
    // и несколько подряд чистых опросов). Иначе заглушка срывается посреди ввода PIN.
    private var bankingStreak = 0
    private var cleanStreak = 0
    private var redacted = false
    private var lastBankingAt = 0L

    /** Последний пакет на переднем плане — передаём в панель, чтобы автовход
     *  срабатывал только на com.bankid.bus, а не на самом Swedbank. */
    var lastPackage: String? = null
        private set

    /** Опрос во время сессии. Гашение банка за ~1 с достаточно, а частый опрос
     *  UsageStats на дешёвом MediaTek заметно грузит систему (источник лагов). */
    fun shouldRedact(now: Long = System.currentTimeMillis()): Boolean {
        val pkg = foregroundPackage(now)
        lastPackage = pkg
        val banking = shouldRedactPackage(pkg)
        if (banking) {
            bankingStreak++
            cleanStreak = 0
            lastBankingAt = now
        } else {
            cleanStreak++
            if (cleanStreak >= RESET_STREAK) bankingStreak = 0
        }
        if (!redacted && bankingStreak >= REDACT_AFTER) redacted = true
        // Снимаем заглушку только при устойчиво «чистом» экране: и время прошло,
        // и несколько подряд опросов не видели банка. Это предотвращает срыв
        // заглушки, когда BankID ненадолго уходит в фон между экранами.
        if (redacted && now - lastBankingAt > UNREDACT_IDLE_MS && cleanStreak >= UNREDACT_STREAK) {
            redacted = false
        }
        return redacted
    }

    companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val BANKID_PACKAGE = "com.bankid.bus"
        private const val LOOKBACK_MS = 10_000L
        private const val STATS_LOOKBACK_MS = 60_000L
        const val REDACT_REASON = "banking-app"

        // Гистерезис: 2 подряд срабатывания → гасим (не чаще, чтобы не мигало).
        // Липкость: снимаем заглушку только если банк не виден 10 секунд подряд
        // и при этом 4 последних опроса были чистыми.
        private const val REDACT_AFTER = 2
        private const val UNREDACT_IDLE_MS = 10_000L
        private const val UNREDACT_STREAK = 4
        private const val RESET_STREAK = 8
    }
}
