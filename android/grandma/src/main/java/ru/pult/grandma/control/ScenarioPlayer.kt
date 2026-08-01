package ru.pult.grandma.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Воспроизведение сценария. Главное правило: **никогда не жать вслепую**.
 *
 * Порядок для каждого шага:
 *  1. Контрольная точка — мы в том же приложении, что при записи? Нет → СТОП.
 *  2. Слой 1: запуск приложения — по интенту, без координат.
 *  3. Слой 2: ищем элемент по идентификатору → тексту → описанию, жмём его.
 *  4. Слой 3: не нашли — бьём по записанным координатам.
 *  5. Ничего не вышло → СТОП с понятной причиной.
 *
 * Остановка — это не отказ продукта, а его обязанность: лучше сказать «что-то
 * изменилось, позову внука», чем нажать неизвестно что на чужом телефоне.
 */
class ScenarioPlayer(
    private val service: AccessibilityService,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
) {

    sealed interface Result {
        data object Done : Result
        data class Stopped(val atStep: Int, val reason: String) : Result
    }

    @Volatile
    private var cancelled = false

    fun cancel() { cancelled = true }

    /**
     * Выполняет сценарий шаг за шагом. Вызывать НЕ с главного потока: метод спит
     * между шагами, ожидая отрисовки экрана.
     */
    fun play(scenario: Scenario, onProgress: (Int, String) -> Unit = { _, _ -> }): Result {
        cancelled = false
        scenario.steps.forEachIndexed { index, step ->
            if (cancelled) return Result.Stopped(index, "остановлено")

            Thread.sleep(step.delayMs.coerceIn(MIN_STEP_PAUSE_MS, MAX_STEP_PAUSE_MS))
            onProgress(index, step.describe())

            val ok = when (step.kind) {
                "launch" -> launch(step)
                "text" -> typeText(step)
                "swipe" -> swipe(step)
                else -> click(step)
            }
            if (!ok) {
                return Result.Stopped(index, "не удалось выполнить: ${step.describe()}")
            }
        }
        return Result.Done
    }

    // ── Слой 1 ────────────────────────────────────────────────────────────────

    private fun launch(step: Step): Boolean {
        val pkg = step.launchPackage ?: return false
        if (currentPackage() == pkg) return true // уже там
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { service.startActivity(intent); true }
            .getOrDefault(false)
            .also { if (it) waitForPackage(pkg) }
    }

    // ── Слои 2 и 3 ────────────────────────────────────────────────────────────

    private fun click(step: Step): Boolean {
        if (!checkpointOk(step)) return false

        // Слой 2 — по смыслу.
        findNode(step)?.let { node ->
            val clicked = node.performActionUpTree()
            if (clicked) return true
            // Элемент нашли, но он сам не кликабельный — жмём по его центру.
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (!rect.isEmpty) return tapAbsolute(rect.exactCenterX(), rect.exactCenterY())
        }

        // Слой 3 — по координатам.
        val x = step.x ?: return false
        val y = step.y ?: return false
        return tapAbsolute(x * screenWidth(), y * screenHeight())
    }

    private fun swipe(step: Step): Boolean {
        if (!checkpointOk(step)) return false
        val svc = service as? RemoteControlService ?: return false
        val w = screenWidth(); val h = screenHeight()
        val x1 = (step.x1 ?: 0.5f) * w
        val y1 = (step.y1 ?: 0.7f) * h
        val x2 = (step.x2 ?: 0.5f) * w
        val y2 = (step.y2 ?: 0.3f) * h
        svc.swipe(x1, y1, x2, y2, SWIPE_MS)
        Thread.sleep(SWIPE_SETTLE_MS) // даём списку доскроллиться перед следующим шагом
        return true
    }

    private fun typeText(step: Step): Boolean {
        if (!checkpointOk(step)) return false
        val text = step.inputText ?: return false
        val root = service.rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: step.viewId?.let { root.findAccessibilityNodeInfosByViewId(it).firstOrNull() }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Ищем элемент: сперва по идентификатору (самое стабильное), потом по тексту. */
    private fun findNode(step: Step): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        step.viewId?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        step.text?.let { t ->
            root.findAccessibilityNodeInfosByText(t)
                .firstOrNull { it.text?.toString() == t || it.contentDescription?.toString() == t }
                ?.let { return it }
        }
        step.desc?.let { d ->
            root.findAccessibilityNodeInfosByText(d)
                .firstOrNull { it.contentDescription?.toString() == d }
                ?.let { return it }
        }
        return null
    }

    /**
     * Нажимаем сам элемент или ближайшего кликабельного родителя: часто текст лежит
     * внутри некликабельного TextView, а обработчик висит на строке целиком.
     */
    private fun AccessibilityNodeInfo.performActionUpTree(): Boolean {
        var node: AccessibilityNodeInfo? = this
        var depth = 0
        while (node != null && depth < PARENT_LOOKUP_DEPTH) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth += 1
        }
        return false
    }

    private fun tapAbsolute(px: Float, py: Float): Boolean {
        val svc = service as? RemoteControlService ?: return false
        svc.tap(px, py)
        return true
    }

    // ── Контрольные точки ─────────────────────────────────────────────────────

    private fun checkpointOk(step: Step): Boolean {
        val expected = step.expectPackage ?: return true
        if (currentPackage() == expected) return true
        // Экран мог не успеть смениться — даём ему шанс, но не бесконечный.
        return waitForPackage(expected)
    }

    private fun waitForPackage(pkg: String): Boolean {
        repeat(WAIT_TICKS) {
            if (currentPackage() == pkg) return true
            Thread.sleep(WAIT_TICK_MS)
        }
        return currentPackage() == pkg
    }

    private fun currentPackage(): String? =
        runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    private companion object {
        const val MIN_STEP_PAUSE_MS = 350L
        const val MAX_STEP_PAUSE_MS = 4000L
        const val SWIPE_MS = 300L
        const val SWIPE_SETTLE_MS = 600L
        const val PARENT_LOOKUP_DEPTH = 4
        const val WAIT_TICKS = 20
        const val WAIT_TICK_MS = 150L
    }
}
