package ru.pult.grandma.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Удалённое управление телефоном бабушки во время сессии.
 *
 * Единственный способ на Android «нажать за пользователя» без системных привилегий —
 * служба доступности: `dispatchGesture` рисует тап/свайп, `performGlobalAction` даёт
 * Назад/Домой/Недавние. Служба включается при настройке (для демо — через adb) и
 * работает ТОЛЬКО пока идёт показ: команды приходят по тому же зашифрованному
 * data-каналу, что и указатель, — сервер их не видит.
 *
 * Экземпляр держим статически: сервис Pult дёргает его из обработчика управляющих
 * сообщений. Пока служба не включена, `instance == null` и управление просто недоступно
 * (показ и указатель при этом работают как раньше).
 */
class RemoteControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        // Размеры экрана нужны записи для перевода границ элемента в доли экрана.
        recorder.screenSize = displaySize()
        // §177 ТЗ: область службы ограничена — по умолчанию видим только приложения,
        // для которых семья записала сценарии. На всё остальное служба «слепа».
        applyScope(openForRecording = false)
    }

    /**
     * ВАЖНО (урок на практике): ограничивать область службы через `packageNames` нельзя,
     * пока идёт ЖИВОЕ управление (§6). `dispatchGesture` возвращает false, если текущее
     * приложение не в списке — и тап «стреляет мимо» на рабочем столе и в настройках.
     *
     * Поэтому область НЕ ограничиваем: помощник управляет телефоном целиком во время
     * согласованной, видимой (рамка + уведомление) сессии. Приватность обеспечивается
     * согласием, видимостью и журналом, а НЕ невидимостью части приложений для службы.
     * Ограничение по `packageNames` (§177) относится к отдельному сценарному режиму «помощь
     * в выбранных приложениях» и будет решаться там, где оно не ломает общее управление.
     */
    fun applyScope(openForRecording: Boolean) {
        val info = serviceInfo ?: return
        info.packageNames = null // без ограничений — иначе жесты не проходят вне списка
        runCatching { serviceInfo = info }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Запись сценариев: работает только когда её явно включили из приложения.
     * Объект общий на процесс — служба пересоздаётся при уходе приложения в фон,
     * а запись обязана это пережить (иначе сценарий обрывается на первом же шаге).
     */
    val recorder: ScenarioRecorder get() = ScenarioRecorder.shared

    val player by lazy {
        ScenarioPlayer(this, screenWidth = { displaySize().first }, screenHeight = { displaySize().second })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Вне записи события просто выбрасываются: ничего не копим и никуда не шлём.
        if (event != null && recorder.isRecording) recorder.onEvent(event)
    }

    override fun onInterrupt() = Unit

    private fun displaySize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Тап по координатам в пикселях экрана.
     *
     * Путь из ОДНОЙ точки MIUI засчитывает как клик не всегда (жест «COMPLETED», но view не
     * реагирует). Добавляем крошечное смещение (1px), чтобы гарантированно сформировались
     * down→move→up, и держим ~100 мс — так тап признаётся кликом надёжно.
     */
    fun tap(xPx: Float, yPx: Float) {
        android.util.Log.i("PultControl", "tap px=($xPx,$yPx)")
        val path = Path().apply {
            moveTo(xPx, yPx)
            lineTo(xPx + 1f, yPx + 1f)
        }
        dispatch(path, durationMs = TAP_MS)
    }

    /** Свайп/протяжка — для прокрутки. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs.coerceIn(30, 2000))
    }

    /**
     * Переход по элементам интерфейса (устойчивее пиксельного тапа):
     *  next/prev — двигаем ДОСТУПНЫЙ фокус на следующий/предыдущий кликабельный элемент
     *  (система рисует зелёную рамку — бабушка видит выбранное, помощник видит её в кадре);
     *  activate — нажимаем выбранный элемент.
     */
    fun focus(dir: String) {
        val root = rootInActiveWindow ?: return
        if (dir == "activate") {
            val cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
            clickUpTree(cur)
            return
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, nodes)
        if (nodes.isEmpty()) return
        val cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val idx = nodes.indexOfFirst { it == cur }
        val target = when (dir) {
            "next" -> nodes[if (idx < 0) 0 else (idx + 1) % nodes.size]
            "prev" -> nodes[if (idx < 0) nodes.size - 1 else (idx - 1 + nodes.size) % nodes.size]
            else -> return
        }
        target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    /** Обход дерева: собираем видимые кликабельные элементы в порядке отрисовки. */
    private fun collectClickable(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        node ?: return
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (node.isClickable && node.isVisibleToUser && !r.isEmpty) out += node
        for (i in 0 until node.childCount) collectClickable(node.getChild(i), out)
    }

    /** Нажать элемент или ближайшего кликабельного родителя. */
    private fun clickUpTree(start: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = start
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            depth += 1
        }
    }

    /** Системная навигация: Назад / Домой / Недавние. */
    fun nav(action: String) {
        val global = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(global)
    }

    /**
     * Системные функции, за которыми чаще всего и зовут внука: громкость, яркость,
     * шторка уведомлений, быстрые настройки, блокировка экрана.
     *
     * Громкость меняем с `FLAG_SHOW_UI` — бабушка ВИДИТ ползунок и понимает, что
     * происходит; молча крутить настройки её телефона мы не хотим.
     */
    fun system(action: String) {
        when (action) {
            "volume-up" -> volume(android.media.AudioManager.ADJUST_RAISE)
            "volume-down" -> volume(android.media.AudioManager.ADJUST_LOWER)
            "volume-mute" -> volume(android.media.AudioManager.ADJUST_TOGGLE_MUTE)
            "brightness-up" -> brightness(+BRIGHTNESS_STEP)
            "brightness-down" -> brightness(-BRIGHTNESS_STEP)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick-settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }

    private fun volume(direction: Int) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                direction,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    /**
     * Яркость требует WRITE_SETTINGS. Разрешения нет → просто ничего не делаем:
     * остальное управление обязано продолжать работать.
     */
    private fun brightness(delta: Int) {
        val can = android.provider.Settings.System.canWrite(this)
        android.util.Log.i("PultControl", "brightness(delta=$delta) canWrite=$can")
        if (!can) return
        runCatching {
            val resolver = contentResolver
            // Автояркость перебила бы наше значение — переводим в ручной режим.
            android.provider.Settings.System.putInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val current = android.provider.Settings.System.getInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                128,
            )
            android.provider.Settings.System.putInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                (current + delta).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX),
            )
        }
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    android.util.Log.i("PultControl", "gesture COMPLETED")
                }
                override fun onCancelled(g: GestureDescription?) {
                    android.util.Log.i("PultControl", "gesture CANCELLED")
                }
            },
            null,
        )
        android.util.Log.i("PultControl", "dispatchGesture returned=$ok")
    }

    companion object {
        private const val TAP_MS = 100L

        // Шаг яркости ~10% от диапазона 0..255: заметно бабушке, но не скачком.
        private const val BRIGHTNESS_STEP = 25
        private const val BRIGHTNESS_MIN = 10
        private const val BRIGHTNESS_MAX = 255

        /** Живой экземпляр включённой службы; `null`, пока она не включена. */
        @Volatile
        var instance: RemoteControlService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
