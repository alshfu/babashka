package ru.pult.grandma.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Запись сценария с самого телефона — внук сидит рядом с бабушкой (или она сама
 * показывает, как делает) и выполняет действия руками.
 *
 * Почему это лучше записи «по проводу»: события доступности отдают не движение пальца,
 * а СМЫСЛ — какой элемент нажали, с каким текстом, в каком приложении. То есть надёжное
 * семантическое представление получается само собой. Координаты снимаем тут же, из границ
 * элемента, — как запасной путь.
 *
 * Записываем только то, что нужно для повтора: что нажали и где мы были. Содержимое
 * экрана никуда не сохраняется и не уходит в сеть.
 */
class ScenarioRecorder(
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Размер экрана задаётся снаружи при каждой привязке службы. Раньше он приходил
     * лямбдой в конструктор, но запись пришлось сделать общей на весь процесс (см. ниже),
     * а служба живёт меньше — поэтому размеры обновляем, а не фиксируем при создании.
     */
    @Volatile
    var screenSize: Pair<Int, Int> = 0 to 0

    private val screenWidth: () -> Int = { screenSize.first }
    private val screenHeight: () -> Int = { screenSize.second }


    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Сообщает сервису о входе/выходе из режима записи. По этому сигналу сервис ОТКЛЮЧАЕТ
     * захват экрана (в записи мы не снимаем пиксели, только действия) и показывает видимый
     * индикатор записи. Так запись остаётся приватной по построению — важно для секретных чатов.
     */
    @Volatile
    var onStateChange: ((Boolean) -> Unit)? = null

    private val steps = mutableListOf<Step>()
    private var lastEventAt = 0L
    private var currentPackage: String? = null
    /** Пакеты, которые записаны как «открыть приложение» — чтобы не дублировать. */
    private var launchedPackage: String? = null
    private var lastScrollAt = 0L
    private var lastScrollDown = false

    fun start() {
        steps.clear()
        lastEventAt = now()
        currentPackage = null
        launchedPackage = null
        isRecording = true
        onStateChange?.invoke(true)
    }

    /**
     * Прямая запись координатного шага — для эмулятора BankID.
     * Тап по копии экрана пишется как click с долями экрана, без accessibility-события.
     */
    fun addCoordinateStep(x: Float, y: Float, pkg: String, label: String? = null) {
        if (!isRecording) return
        steps += Step(
            kind = "click",
            delayMs = takeDelay(),
            expectPackage = pkg,
            text = label,
            x = x,
            y = y,
        )
    }

    /** Останавливает запись и отдаёт снятые шаги. */
    fun stop(): List<Step> {
        isRecording = false
        onStateChange?.invoke(false)
        return steps.toList()
    }

    fun preview(): List<Step> = steps.toList()

    fun onEvent(event: AccessibilityEvent) {
        if (!isRecording) return
        val pkg = event.packageName?.toString() ?: return
        // Себя не записываем: нажатия в самом Пульте к сценарию не относятся.
        if (pkg == OWN_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event, pkg)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event, pkg)
        }
    }

    /**
     * Листание. Одно физическое движение пальца порождает пачку событий скролла —
     * схлопываем их в ОДИН шаг-свайп (дебаунс по времени и направлению). Направление
     * берём из знака `scrollDeltaY`; свайп рисуем внутри границ прокручиваемого списка.
     *
     * Именно это делает возможным «пролистать до семейной группы»: сначала повторяем
     * листание, а нужный элемент потом находим по имени.
     */
    private fun onScrolled(event: AccessibilityEvent, pkg: String) {
        val down = scrollIsDown(event) ?: return
        val at = now()
        val sameBurst = at - lastScrollAt < SCROLL_DEBOUNCE_MS && down == lastScrollDown
        lastScrollAt = at
        lastScrollDown = down
        if (sameBurst) return // то же движение пальца — второй шаг не плодим

        val w = screenWidth().takeIf { it > 0 } ?: return
        val h = screenHeight().takeIf { it > 0 } ?: return
        val bounds = Rect().also { event.source?.getBoundsInScreen(it) }
        val area = if (!bounds.isEmpty) bounds else Rect(0, 0, w, h)

        val cx = area.exactCenterX() / w
        val top = area.top / h.toFloat()
        val bottom = area.bottom / h.toFloat()
        val hi = top + (bottom - top) * 0.25f
        val lo = top + (bottom - top) * 0.75f
        // Листаем вниз → палец снизу вверх (от lo к hi), и наоборот.
        val (fromY, toY) = if (down) lo to hi else hi to lo

        steps += Step(
            kind = "swipe",
            delayMs = takeDelay(),
            expectPackage = pkg,
            x1 = cx, y1 = fromY, x2 = cx, y2 = toY,
        )
    }

    /** true — листаем вниз, false — вверх, null — движения по вертикали нет. */
    private fun scrollIsDown(event: AccessibilityEvent): Boolean? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val dy = event.scrollDeltaY
            if (dy != 0 && dy != Int.MIN_VALUE) return dy > 0
        }
        // Старые версии: судим по смене индексов в списке.
        val from = event.fromIndex
        val to = event.toIndex
        if (from >= 0 && to >= 0 && from != to) return to > from
        return null
    }

    /**
     * Смена приложения. Если человек перешёл в другое приложение — это шаг «открыть его».
     * Слой 1: при воспроизведении запустим по интенту, без единой координаты.
     */
    private fun onWindowChanged(pkg: String) {
        if (pkg == currentPackage) return
        currentPackage = pkg
        if (pkg == launchedPackage || pkg in IGNORED_PACKAGES) return
        launchedPackage = pkg
        steps += Step(
            kind = "launch",
            delayMs = takeDelay(),
            launchPackage = pkg,
            expectPackage = pkg,
        )
    }

    private fun onClicked(event: AccessibilityEvent, pkg: String) {
        val node = event.source
        val bounds = Rect().also { node?.getBoundsInScreen(it) }
        val w = screenWidth().takeIf { it > 0 } ?: return
        val h = screenHeight().takeIf { it > 0 } ?: return

        // Координаты — центр элемента; это точнее, чем сырая точка касания.
        val hasBounds = !bounds.isEmpty
        val fx = if (hasBounds) bounds.exactCenterX() / w else null
        val fy = if (hasBounds) bounds.exactCenterY() / h else null

        steps += Step(
            kind = "click",
            delayMs = takeDelay(),
            expectPackage = pkg,
            viewId = node?.viewIdResourceName,
            text = node?.text?.toString()?.take(TEXT_LIMIT)
                ?: event.text.joinToString(" ").takeIf { it.isNotBlank() }?.take(TEXT_LIMIT),
            desc = node?.contentDescription?.toString()?.take(TEXT_LIMIT),
            x = fx,
            y = fy,
        )
        node?.recycleCompat()
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        val typed = event.text.joinToString("").takeIf { it.isNotBlank() } ?: return
        // Схлопываем посимвольный ввод в один шаг: иначе получим сто шагов на одно слово.
        val last = steps.lastOrNull()
        if (last?.kind == "text" && last.expectPackage == pkg) {
            steps[steps.lastIndex] = last.copy(inputText = typed.take(TEXT_LIMIT))
            return
        }
        steps += Step(
            kind = "text",
            delayMs = takeDelay(),
            expectPackage = pkg,
            inputText = typed.take(TEXT_LIMIT),
            viewId = event.source?.viewIdResourceName,
        )
    }

    /** Пауза с прошлого шага — чтобы при повторе давать экрану столько же времени. */
    private fun takeDelay(): Long {
        val at = now()
        val delta = (at - lastEventAt).coerceIn(0, MAX_DELAY_MS)
        lastEventAt = at
        return delta
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching { recycle() }
        }
    }

    companion object {
        /**
         * Запись живёт на уровне процесса, а НЕ внутри службы доступности.
         *
         * Причина найдена опытным путём: когда приложение уходит в фон (а оно обязано
         * уйти — человек выполняет действия в других приложениях), прошивка пересоздаёт
         * службу. Экземпляр службы новый — и запись вместе с ним обнулялась бы прямо
         * посреди сценария. Общий объект переживает пересоздание службы.
         */
        val shared = ScenarioRecorder()

        private const val OWN_PACKAGE = "se.pult.app"
        private const val TEXT_LIMIT = 80
        private const val MAX_DELAY_MS = 4000L
        private const val SCROLL_DEBOUNCE_MS = 450L

        /** Системная обвязка, которая мелькает между экранами и шагом не является. */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )
    }
}
