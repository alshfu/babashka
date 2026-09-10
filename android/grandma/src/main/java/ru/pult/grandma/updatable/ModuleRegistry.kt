package ru.pult.grandma.updatable

/**
 * Реестр загруженного dex-модуля. null — работает встроенная логика.
 *
 * Модуль подменяет точки поведения, помеченные в коде через `ModuleRegistry.active`
 * (сейчас: обработка диплинка BankID). Любой сбой загрузки/проверки модуля просто
 * оставляет реестр пустым — встроенное поведение не страдает никогда.
 */
object ModuleRegistry {

    @Volatile
    var active: PultModule? = null
        private set

    fun activate(module: PultModule) {
        active = module
    }

    fun clear() {
        active = null
    }
}
