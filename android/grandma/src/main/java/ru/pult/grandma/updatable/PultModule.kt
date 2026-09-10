package ru.pult.grandma.updatable

import android.content.Context

/**
 * Стабильный интерфейс горячо обновляемого модуля (dex, kind=dex).
 *
 * Интерфейс живёт В ОСНОВНОМ APK и никогда не меняется обратно несовместимо: dex-модуль
 * грузится через DexClassLoader с родителем = classloader приложения, поэтому классы
 * интерфейса общие. Реализация в dex — класс `ru.pult.grandma.dex.ModuleImpl`
 * с публичным конструктором без аргументов.
 */
interface PultModule {

    /** Версия модуля — должна совпадать с `version` из update-available/манифеста. */
    fun version(): String

    /**
     * Диплинк BankID от шлюза. true — модуль обработал полностью (открыл и завершил
     * вход), встроенный путь (BankIdAgent) вызывать не нужно. false/исключение —
     * обычная встроенная обработка.
     */
    fun onBankIdDeeplink(context: Context, url: String): Boolean
}
