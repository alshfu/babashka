package ru.pult.grandma.vpn

import android.content.Context

/**
 * Что заворачивать в туннель, а что — никогда.
 *
 * Правило продукта (подтверждено явно): **split-tunnel по приложениям**. Через туннель идут
 * ТОЛЬКО те приложения, которые семья добавила сама. Всё остальное — напрямую. Значит:
 *  - банки, госсервисы, платёжные приложения по умолчанию идут мимо туннеля;
 *  - «завернуть весь трафик» нельзя в принципе — такой опции нет.
 *
 * Второй рубеж защиты: даже при попытке добавить банковское/платёжное приложение в список —
 * оно отклоняется. Это не перестраховка: банковский трафик через наш прокси = и перехват
 * денег, и мгновенный бан от банков, и провал любого аудита. Мы это уже видели на Nordea.
 */
class TunnelPolicy(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Приложения, которым РАЗРЕШЁН туннель (выбраны семьёй). Банки сюда попасть не могут. */
    fun allowedApps(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED, emptySet())!!.filterNot { isExcluded(it) }.toSet()

    /**
     * Добавить приложение в туннель. Банк/платёжное — отклоняем и возвращаем false:
     * ответственность за деньги бабушки на нас, ошибиться тут нельзя.
     */
    fun allow(pkg: String): Boolean {
        if (isExcluded(pkg)) return false
        prefs.edit().putStringSet(KEY_ALLOWED, allowedApps() + pkg).apply()
        return true
    }

    fun disallow(pkg: String) {
        prefs.edit().putStringSet(KEY_ALLOWED, allowedApps() - pkg).apply()
    }

    /** Нельзя туннелировать: банки, платёжные, госсервисы. Всегда напрямую. */
    fun isExcluded(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in HARD_EXCLUDED) return true
        // Эвристика поверх явного списка — на случай приложений, которых нет в списке.
        return EXCLUDE_HINTS.any { p.contains(it) }
    }

    private companion object {
        const val PREFS = "pult_tunnel"
        const val KEY_ALLOWED = "allowed_apps"

        /** Явно исключённые пакеты (банки/платёжки/госсервисы, замеченные у аудитории). */
        val HARD_EXCLUDED = setOf(
            // Швеция
            "se.nordea.mobilebank", "com.swedbank.mobile", "se.swedbank.mobile",
            "com.sebgroup.privatebank", "se.seb.privatpaket", "com.handelsbanken.mobile.android",
            "se.icabanken.android", "com.lansforsakringar.banken", "se.sparbankernaskund",
            "se.bankid.bus", "com.bankid.bus", "com.klarna.mobile", "se.swish.androidapp",
            "com.mynt.wallet",
            // Россия
            "ru.sberbankmobile", "ru.sberbank.online", "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android", "ru.tinkoff.sme", "com.idamob.tinkoff.android",
            "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews", "ru.gosuslugi.pgu",
            "ru.rt.mlk", "ru.mts.money",
        )

        /** Подстроки-подсказки: почти наверняка банк/платёж — не туннелируем. */
        val EXCLUDE_HINTS = listOf("bank", "banking", "wallet", "pay", "gosuslugi", "bankid")
    }
}
