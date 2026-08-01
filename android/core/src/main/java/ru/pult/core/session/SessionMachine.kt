package ru.pult.core.session

import ru.pult.core.protocol.Role

/**
 * Состояния сессии на устройстве (docs/architecture.md §5).
 *
 * Смысл этого класса — не «удобно хранить состояние», а сделать невозможным запуск
 * захвата экрана раньше времени: `canCaptureScreen` истинно ровно в одном состоянии,
 * в которое нельзя попасть, минуя согласие бабушки и проверку MAC.
 */
class SessionMachine(private val role: String) {

    enum class State {
        /** Готов: сокет держим, ничего не передаём. */
        IDLE,

        /** Запрос висит: помощник отправил, бабушка ещё не ответила. */
        PENDING,

        /** Бабушка нажала «Разрешить»; идёт обмен SDP и проверка пары. */
        CONSENTED,

        /** Пара подтверждена с обеих сторон — только здесь можно показывать экран. */
        CONNECTED,
    }

    var state: State = State.IDLE
        private set

    var sessionId: String? = null
        private set

    /** Единственное место, где разрешён захват экрана. */
    val canCaptureScreen: Boolean
        get() = state == State.CONNECTED

    fun onHelpRequest(sessionId: String) {
        require(state == State.IDLE) { "запрос помощи в состоянии $state" }
        this.sessionId = sessionId
        state = State.PENDING
    }

    fun onConsent() {
        require(state == State.PENDING) { "согласие в состоянии $state" }
        state = State.CONSENTED
    }

    /**
     * Вызывается ТОЛЬКО после успешной проверки MAC второй стороны.
     * Роль здесь важна: бабушка переходит в CONNECTED, проверив `macH`, помощник — `macG`.
     */
    fun onPeerAuthenticated() {
        require(state == State.CONSENTED) { "подтверждение пары в состоянии $state" }
        state = State.CONNECTED
    }

    fun onEnd(): String? {
        val ended = sessionId
        sessionId = null
        state = State.IDLE
        return ended
    }

    /** Сообщение чужой или устаревшей сессии игнорируется, а не обрабатывается «как-нибудь». */
    fun belongsToCurrent(sessionId: String?): Boolean =
        sessionId != null && sessionId == this.sessionId

    /** Инициировать помощь может только помощник (docs/protocol.md §9). */
    fun mayInitiate(): Boolean = role == Role.HELPER

    /** Давать согласие может только бабушка. */
    fun mayConsent(): Boolean = role == Role.GRANDMA
}
