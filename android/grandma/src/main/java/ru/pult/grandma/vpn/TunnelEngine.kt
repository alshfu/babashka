package ru.pult.grandma.vpn

import android.os.ParcelFileDescriptor

/**
 * Движок туннеля — то, что реально гонит пакеты из TUN в прокси и обратно.
 *
 * Вынесен за интерфейс сознательно: каркас (стадия 2) готов и безопасен без него, а сам
 * прокси (Xray-core, стадия 3) подключается сюда отдельным модулем. Пока движка нет —
 * `None`, и сервис НЕ поднимает «мёртвый» туннель, чтобы не отрезать выбранные приложения.
 */
interface TunnelEngine {

    /** Готов ли движок реально гнать трафик. Если нет — туннель не поднимаем. */
    fun isReady(): Boolean

    /**
     * Запустить перекачку. `tun` — файловый дескриптор TUN-интерфейса; `protect` защищает
     * сокеты движка от зацикливания обратно в туннель (стандарт VpnService).
     */
    fun start(tun: ParcelFileDescriptor, config: TunnelConfig, protect: (Int) -> Boolean)

    fun stop()

    /** Заглушка стадии 2: движка нет. Каркас проверяем без реальной перекачки. */
    object None : TunnelEngine {
        override fun isReady() = false
        override fun start(tun: ParcelFileDescriptor, config: TunnelConfig, protect: (Int) -> Boolean) = Unit
        override fun stop() = Unit
    }
}
