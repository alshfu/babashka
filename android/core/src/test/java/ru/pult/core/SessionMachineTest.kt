package ru.pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pult.core.protocol.Role
import ru.pult.core.session.SessionMachine

/**
 * Главное, что здесь проверяется: до захвата экрана нельзя добраться в обход
 * согласия бабушки и проверки пары (docs/security.md §6, пункты 1–2).
 */
class SessionMachineTest {

    @Test
    fun `захват экрана возможен только после согласия и проверки пары`() {
        val machine = SessionMachine(Role.GRANDMA)
        assertFalse(machine.canCaptureScreen)

        machine.onHelpRequest("s1")
        assertFalse("запрос сам по себе доступа не даёт", machine.canCaptureScreen)

        machine.onConsent()
        assertFalse("согласие без проверки пары — ещё не показ", machine.canCaptureScreen)

        machine.onPeerAuthenticated()
        assertTrue(machine.canCaptureScreen)

        machine.onEnd()
        assertFalse(machine.canCaptureScreen)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `подтверждение пары в обход согласия невозможно`() {
        SessionMachine(Role.GRANDMA).onPeerAuthenticated()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `согласие без запроса невозможно`() {
        SessionMachine(Role.GRANDMA).onConsent()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `второй запрос поверх идущей сессии отвергается`() {
        val machine = SessionMachine(Role.HELPER)
        machine.onHelpRequest("s1")
        machine.onHelpRequest("s2")
    }

    @Test
    fun `сообщения чужой сессии игнорируются`() {
        val machine = SessionMachine(Role.GRANDMA)
        machine.onHelpRequest("s1")
        assertTrue(machine.belongsToCurrent("s1"))
        assertFalse(machine.belongsToCurrent("s2"))
        assertFalse(machine.belongsToCurrent(null))
    }

    @Test
    fun `завершение возвращает машину в исходное состояние`() {
        val machine = SessionMachine(Role.HELPER)
        machine.onHelpRequest("s1")
        assertEquals("s1", machine.onEnd())
        assertEquals(SessionMachine.State.IDLE, machine.state)
        assertNull(machine.sessionId)
        machine.onHelpRequest("s2") // после завершения снова можно
    }

    @Test
    fun `роли асимметричны инициировать может помощник, соглашаться — бабушка`() {
        assertTrue(SessionMachine(Role.HELPER).mayInitiate())
        assertFalse(SessionMachine(Role.GRANDMA).mayInitiate())
        assertTrue(SessionMachine(Role.GRANDMA).mayConsent())
        assertFalse(SessionMachine(Role.HELPER).mayConsent())
    }
}
