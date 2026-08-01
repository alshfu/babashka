package ru.pult.grandma.net

import android.content.Context

/**
 * Где телефону брать связь — устойчивый к блокировкам список адресов сигналинга.
 *
 * Идея (в свете того, что РКН активно режет known-адреса): приложение не привязано к одному
 * входу. У него есть список — адрес пары плюс запасные (другие домены/CDN/порты). При обрыве
 * SignalingClient перебирает их сам. Список можно ОБНОВИТЬ из внешнего подписанного источника
 * («скажем приложению, где сегодня брать связь»), не переставляя APK.
 *
 * Обновление принимаем только с проверкой подписи (см. EndpointRefresher, стадия 1б) — чтобы
 * компрометация источника не увела телефон на чужой сервер. Здесь — только хранение и выдача.
 */
class EndpointStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Итоговый список для клиента: сначала адрес пары (основной), затем сохранённые запасные,
     * без дубликатов. Основной всегда первый — на нём работаем, пока он жив.
     */
    fun endpoints(primary: String): List<String> {
        val extra = load()
        return (listOf(primary) + extra).distinct()
    }

    /** Сохранённые запасные адреса (без основного). */
    fun load(): List<String> =
        prefs.getString(KEY_LIST, null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** Заменить список запасных адресов (после проверенного обновления из источника). */
    fun save(endpoints: List<String>) {
        prefs.edit()
            .putString(KEY_LIST, endpoints.joinToString("\n"))
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun lastUpdated(): Long = prefs.getLong(KEY_UPDATED, 0L)

    private companion object {
        const val PREFS = "pult_endpoints"
        const val KEY_LIST = "fallback_endpoints"
        const val KEY_UPDATED = "updated_at"
    }
}
