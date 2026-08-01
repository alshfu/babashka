package ru.pult.grandma.calls

import android.content.Context
import ru.pult.core.calls.PhoneNumbers
import ru.pult.grandma.PultApp

/**
 * Белый список звонков (docs/call-screening.md).
 *
 * Хранится локально и никуда не отправляется: «облачная база номеров» означала бы
 * отправку на сервер того, кто звонит бабушке, — ровно того, чего мы не делаем.
 */
class Whitelist(context: Context) {

    private val prefs = PultApp.prefs(context)

    enum class Mode {
        /** Всё, чего нет в списке, отклоняется. По умолчанию. */
        STRICT,

        /** Звонок проходит, но помечается «возможно мошенник». */
        SOFT,

        /** Фильтр выключен по явному решению семьи. */
        OFF,
    }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.STRICT.name)!!) }
            .getOrDefault(Mode.STRICT)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    fun numbers(): Set<String> = prefs.getStringSet(KEY_NUMBERS, emptySet())!!.toSet()

    fun add(number: String) {
        val normalized = PhoneNumbers.normalize(number) ?: return
        prefs.edit().putStringSet(KEY_NUMBERS, numbers() + normalized).apply()
    }

    fun remove(number: String) {
        val normalized = PhoneNumbers.normalize(number) ?: return
        prefs.edit().putStringSet(KEY_NUMBERS, numbers() - normalized).apply()
    }

    /** Номер помощника добавляется автоматически при спаривании и не удаляется случайно. */
    fun addHelper(number: String) = add(number)

    fun shouldAllow(incoming: String?): Boolean = when (mode) {
        Mode.OFF -> true
        Mode.SOFT -> PhoneNumbers.shouldAllow(incoming, numbers(), strict = false)
        Mode.STRICT -> PhoneNumbers.shouldAllow(incoming, numbers(), strict = true)
    }

    private companion object {
        const val KEY_MODE = "whitelist_mode"
        const val KEY_NUMBERS = "whitelist_numbers"
    }
}
