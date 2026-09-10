package ru.pult.grandma.control

import android.os.SystemClock

/**
 * Режим BankID: чей сейчас вход — удалённый (по диплинку от шлюза) или местный
 * (бабушка сама открыла BankID).
 *
 * Это предохранитель автоматики: auto-PIN и тапы BankIdAgent допустимы только в окне
 * после удалённого диплинка. Местный пользователь не должен заметить никакого
 * вмешательства — при LOCAL автономные действия запрещены (см. BankIdAgent.complete).
 */
object BankIdMode {

    enum class Mode { LOCAL, REMOTE }

    /** Сервис шлёт помощнику bankid-mode при смене режима (если control-канал открыт). */
    var onModeChange: ((Mode) -> Unit)? = null

    @Volatile
    private var lastRemoteBankIdAt = 0L

    @Volatile
    private var reported: Mode = Mode.LOCAL

    /** Диплинк BankID пришёл от сервера — вход удалённый, автоматика разрешена. */
    fun onRemoteDeeplink(now: Long = SystemClock.elapsedRealtime()) {
        lastRemoteBankIdAt = now
        set(Mode.REMOTE)
    }

    /** BankID на переднем плане: удалённый (недавний диплинк) или местный вход. */
    fun onBankIdForeground(now: Long = SystemClock.elapsedRealtime()) {
        set(if (isRemote(now)) Mode.REMOTE else Mode.LOCAL)
    }

    /** Автоматика допустима только в окне после удалённого диплинка. */
    fun isRemote(now: Long = SystemClock.elapsedRealtime()): Boolean =
        lastRemoteBankIdAt > 0 && now - lastRemoteBankIdAt < REMOTE_WINDOW_MS

    private fun set(mode: Mode) {
        if (mode == reported) return
        reported = mode
        onModeChange?.invoke(mode)
    }

    private const val REMOTE_WINDOW_MS = 5 * 60 * 1000L
}
