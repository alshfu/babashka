package ru.pult.grandma.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.pult.grandma.BuildConfig

/**
 * Удалённое управление телефоном бабушки во время сессии.
 *
 * Единственный способ на Android «нажать за пользователя» без системных привилегий —
 * служба доступности: `dispatchGesture` рисует тап/свайп, `performGlobalAction` даёт
 * Назад/Домой/Недавние. Служба включается при настройке (для демо — через adb) и
 * работает ТОЛЬКО пока идёт показ: команды приходят по зашифрованному
 * data-каналу — сервер их не видит.
 *
 * Экземпляр держим статически: сервис Pult дёргает его из обработчика управляющих
 * сообщений. Пока служба не включена, `instance == null` и управление просто недоступно
 * (показ при этом работает как раньше).
 */
class RemoteControlService : AccessibilityService() {

    /**
     * Операционный канал «shell → жест» без сессии: `am broadcast` из adb (uid 2000)
     * шлёт тап/свайп напрямую в dispatchGesture. Нужен для автономных сценариев
     * (BankID-вход 24/7), где сессионный data-канал недоступен, а инъекция `input`
     * на защищённых окнах (lockscreen, FLAG_SECURE) режется MIUI.
     *
     * Безопасность: ресивер требует у отправителя WRITE_SECURE_SETTINGS (держат
     * shell/система) — посторонние приложения его не получают.
     */
    private val shellTapReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != ACTION_SHELL_TAP) return
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            when (intent.getStringExtra("cmd")) {
                "tap" -> {
                    val fx = intent.getFloatExtra("fx", -1f)
                    val fy = intent.getFloatExtra("fy", -1f)
                    if (fx in 0f..1f && fy in 0f..1f) tap(fx * w, fy * h)
                }
                "swipe" -> {
                    val fx1 = intent.getFloatExtra("fx1", -1f); val fy1 = intent.getFloatExtra("fy1", -1f)
                    val fx2 = intent.getFloatExtra("fx2", -1f); val fy2 = intent.getFloatExtra("fy2", -1f)
                    val ms = intent.getLongExtra("ms", 250L)
                    if (fx1 in 0f..1f && fy1 in 0f..1f && fx2 in 0f..1f && fy2 in 0f..1f) {
                        swipe(fx1 * w, fy1 * h, fx2 * w, fy2 * h, ms)
                    }
                }
                "back" -> nav("back")
                "home" -> nav("home")
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        android.util.Log.i("PultControl", "onServiceConnected: instance set")
        applyScope()
        runCatching {
            registerReceiver(
                shellTapReceiver,
                android.content.IntentFilter(ACTION_SHELL_TAP),
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                null,
                android.content.Context.RECEIVER_EXPORTED,
            )
            android.util.Log.i("PultControl", "shell tap receiver registered")
        }.onFailure {
            android.util.Log.w("PultControl", "shell tap receiver: ${it.message}")
        }
        // Служба доступности — один из самых защищённых компонентов: система сама её
        // перезапускает. Пока она жива, держим на ней вахту над основным сервисом.
        guardMainService()
    }

    /**
     * ВАЖНО (урок на практике): ограничивать область службы через `packageNames` нельзя,
     * пока идёт ЖИВОЕ управление (§6). `dispatchGesture` возвращает false, если текущее
     * приложение не в списке — и тап «стреляет мимо» на рабочем столе и в настройках.
     *
     * Поэтому область НЕ ограничиваем: помощник управляет телефоном целиком во время
     * согласованной, видимой (уведомление) сессии. Приватность обеспечивается
     * видимостью и журналом, а НЕ невидимостью части приложений для службы.
     */
    fun applyScope() {
        val info = serviceInfo ?: return
        info.packageNames = null // без ограничений — иначе жесты не проходят вне списка
        runCatching { serviceInfo = info }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(shellTapReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Каждое событие доступности — повод сверить, жив ли основной сервис
        // (троттлинг внутри guardMainService): события идут почти непрерывно,
        // пока телефоном пользуются, и вахта на них не спит.
        guardMainService()
        // Отладочный автоклик системного диалога захвата экрана.
        // На Android 15+ appops PROJECT_MEDIA allow не всегда подавляет системное окно
        // (особенно на первом запуске), поэтому на этапе разработки кликаем за бабушку.
        if (BuildConfig.DEBUG && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString().orEmpty()
            if (className.contains("MediaProjectionPermissionActivity")) {
                Handler(Looper.getMainLooper()).postDelayed({ autoConfirmCaptureDialog() }, 300)
            }
        }
    }

    /** Находит в системном диалоге захвата кнопку подтверждения и нажимает её. */
    private fun autoConfirmCaptureDialog() {
        val root = rootInActiveWindow ?: return
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        // Предпочитаем узел с типичным текстом кнопки "Start now" / "Начать".
        val texts = listOf("start now", "begin", "начать", "пуск", "允许", "許可", "start")
        val byText = candidates.find { node ->
            val t = node.text?.toString().orEmpty().lowercase()
            texts.any { t.contains(it) }
        }
        val target = byText ?: candidates.lastOrNull()
        if (target != null) {
            android.util.Log.i("PultControl", "auto-confirming media projection dialog")
            clickUpTree(target)
        }
    }

    override fun onInterrupt() = Unit

    private var lastGuardAt = 0L

    /**
     * Вахта выживания основного сервиса на самом защищённом компоненте приложения.
     * Система перезапускает accessibility-службу почти всегда — значит, пока вахта жива,
     * PultService не должен быть мёртв. Троттлинг 30 с: события доступности идут почти
     * непрерывно, а проверка через ActivityManager не бесплатная.
     */
    private fun guardMainService() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGuardAt < GUARD_INTERVAL_MS) return
        lastGuardAt = now
        if (!ru.pult.grandma.service.PultService.isRunning(this)) {
            android.util.Log.w("PultControl", "guard: PultService мёртв — поднимаем")
            ru.pult.grandma.service.PultService.start(this)
        }
    }

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

        /** Экшн broadcast-канала «shell → жест» (см. shellTapReceiver). */
        const val ACTION_SHELL_TAP = "ru.pult.grandma.control.action.SHELL_TAP"

        /** Троттлинг вахты выживания (см. guardMainService): не чаще раза в 30 с. */
        private const val GUARD_INTERVAL_MS = 30_000L

        // Шаг яркости ~10% от диапазона 0..255: заметно бабушке, но не скачком.
        private const val BRIGHTNESS_STEP = 25
        private const val BRIGHTNESS_MIN = 10
        private const val BRIGHTNESS_MAX = 255

        /** Живой экземпляр включённой службы; `null`, пока она не включена. */
        @Volatile
        var instance: RemoteControlService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        /** Дерево доступности как JSON — диагностика для помощника по data-каналу. */
        fun dumpTreeJson(): String? {
            val service = instance ?: return null
            val sb = StringBuilder()
            sb.append("[")
            var first = true
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                if (node.isVisibleToUser && (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || node.isClickable)) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append("{")
                    sb.append("\"cls\":\"${esc(cls)}\",")
                    sb.append("\"text\":\"${esc(text)}\",")
                    sb.append("\"desc\":\"${esc(desc)}\",")
                    sb.append("\"id\":\"${esc(id.substringAfterLast('/'))}\",")
                    sb.append("\"bounds\":[${rect.left},${rect.top},${rect.right},${rect.bottom}],")
                    sb.append("\"clickable\":${node.isClickable}")
                    sb.append("}")
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it, depth + 1) }
                }
            }
            val roots = mutableListOf<AccessibilityNodeInfo>()
            runCatching { service.rootInActiveWindow?.let { roots.add(it) } }
            // Диалоги поверх активности — отдельные окна: обходим ВСЕ, иначе, например,
            // код/порт из системного диалога спаривания в дамп не попадают.
            runCatching {
                service.windows?.forEach { win ->
                    win?.root?.let { root ->
                        if (roots.none { it == root }) roots.add(root)
                    }
                }
            }
            for (root in roots) runCatching { walk(root, 0) }
            sb.append("]")
            return sb.toString()
        }

        /**
         * Скролл списка ДЕЙСТВИЕМ доступности (ACTION_SCROLL_FORWARD/BACKWARD), а не жестом:
         * на MIUI dispatchGesture по спискам настроек прокручивает плохо или непрокручивает.
         */
        fun scrollList(forward: Boolean): Boolean {
            val service = instance ?: return false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.actionList.any { it.id == action }) return node
                for (i in 0 until node.childCount) {
                    val found = node.getChild(i)?.let { findScrollable(it) }
                    if (found != null) return found
                }
                return null
            }
            val roots = mutableListOf<AccessibilityNodeInfo>()
            runCatching { service.rootInActiveWindow?.let { roots.add(it) } }
            runCatching {
                service.windows?.forEach { win ->
                    win?.root?.let { root ->
                        if (roots.none { it == root }) roots.add(root)
                    }
                }
            }
            for (root in roots) {
                val scrollable = findScrollable(root)
                if (scrollable != null && scrollable.performAction(action)) return true
            }
            return false
        }

        /**
         * Дамп дерева доступности в файл — ТОЛЬКО для отладки на устройстве владельца.
         * Нужен, чтобы снять экраны защищённых приложений (BankID):
         * uiautomator их не видит, а служба видит всё дерево.
         */
        fun dumpTree(outputPath: String): Boolean {
            val service = instance ?: run {
                android.util.Log.w("PultControl", "dumpTree: instance is null")
                return false
            }
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || node.isClickable) {
                    sb.append("  ".repeat(depth))
                    sb.append("$cls text='$text' desc='$desc' id='${id.substringAfterLast('/')}' ")
                    sb.append("bounds=[$rect] clickable=${node.isClickable}\n")
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it, depth + 1) }
                }
            }
            // На MIUI rootInActiveWindow часто null даже при живом окне. Берём все окна
            // через getWindows() и выбираем активное или приложение с наибольшим деревом.
            val roots = mutableListOf<AccessibilityNodeInfo>()
            runCatching { service.rootInActiveWindow?.let { roots.add(it) } }
            if (roots.isEmpty()) {
                runCatching {
                    service.windows?.forEach { win ->
                        win?.root?.let { roots.add(it) }
                    }
                }
            }
            if (roots.isEmpty()) {
                android.util.Log.w("PultControl", "dumpTree: no accessible windows")
                return false
            }
            android.util.Log.i("PultControl", "dumpTree: ${roots.size} window(s)")
            for (root in roots) {
                runCatching { walk(root, 0) }
                sb.append("\n---\n")
            }
            return runCatching {
                java.io.File(outputPath).writeText(sb.toString())
                true
            }.getOrDefault(false)
        }
    }
}
