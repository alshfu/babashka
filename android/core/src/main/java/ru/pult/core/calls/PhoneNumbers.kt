package ru.pult.core.calls

/**
 * Нормализация номеров для белого списка (docs/call-screening.md §2).
 *
 * Один и тот же номер бабушка запишет как `+7 999 …`, оператор пришлёт как `8999…`,
 * а в контактах он окажется без кода города. Если сравнивать строки как есть, фильтр
 * будет молча пропускать мошенников и блокировать своих — то есть работать наоборот.
 */
object PhoneNumbers {

    /** Экстренные номера. Блокировать их нельзя ни в каком режиме и никакими настройками. */
    val EMERGENCY: Set<String> = setOf("112", "101", "102", "103", "104", "911")

    private const val SIGNIFICANT_DIGITS = 10

    /** Только цифры; ведущие 8/7 отбрасываются вместе с остальным «хвостом» кода страны. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter(Char::isDigit)
        if (digits.isEmpty()) return null
        if (digits in EMERGENCY) return digits
        return if (digits.length >= SIGNIFICANT_DIGITS) digits.takeLast(SIGNIFICANT_DIGITS) else digits
    }

    fun isEmergency(raw: String?): Boolean = normalize(raw) in EMERGENCY

    /** Скрытый номер — это всегда «неизвестный»: подставить пустую строку и пропустить нельзя. */
    fun isHidden(raw: String?): Boolean = normalize(raw) == null

    fun matches(a: String?, b: String?): Boolean {
        val left = normalize(a) ?: return false
        val right = normalize(b) ?: return false
        return left == right
    }

    /** Решение фильтра. Экстренные — всегда мимо белого списка. */
    fun shouldAllow(incoming: String?, whitelist: Collection<String>, strict: Boolean): Boolean {
        if (isEmergency(incoming)) return true
        if (isHidden(incoming)) return !strict
        val number = normalize(incoming)
        val known = whitelist.any { normalize(it) == number }
        return known || !strict
    }
}
