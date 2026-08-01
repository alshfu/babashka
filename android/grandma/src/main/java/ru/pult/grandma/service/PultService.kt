package ru.pult.grandma.service

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.pult.core.net.SignalingClient
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.core.pairing.PairRecord
import ru.pult.core.pairing.PairStore
import ru.pult.core.protocol.PairAuth
import ru.pult.core.protocol.Role
import ru.pult.core.protocol.Signal
import ru.pult.grandma.PultApp
import ru.pult.grandma.session.ForegroundAppWatcher
import android.view.WindowManager
import ru.pult.grandma.control.RemoteControlService
import ru.pult.grandma.net.EndpointStore
import ru.pult.grandma.session.SessionController
import ru.pult.grandma.session.SessionJournal
import ru.pult.grandma.push.PultMessagingService
import ru.pult.grandma.session.WebRtcScreenTransport
import ru.pult.grandma.ui.ConsentActivity
import ru.pult.grandma.ui.overlay.SessionOverlay

/**
 * Видимый фоновый сервис (ТЗ п.4).
 *
 * Бабушка приложение не открывает — оно живёт здесь: держит связь с сигналингом,
 * показывает запрос помощи, ведёт сессию. И всё это время висит постоянное уведомление.
 *
 * Выживание в фоне на MIUI/Huawei — отдельная работа (docs/android-grandma.md §3):
 * автозапуск, отключение оптимизации батареи, FCM-пробуждение и сторож на WorkManager.
 */
class PultService : LifecycleService() {

    private lateinit var pairStore: PairStore
    private lateinit var journal: SessionJournal
    private var overlay: SessionOverlay? = null
    private var signaling: SignalingClient? = null
    private var controller: SessionController? = null
    private var foregroundJob: Job? = null
    private var peerName: String? = null
    private lateinit var keepAlive: KeepAlive

    // Мост на главный поток: колбэки datachannel и присутствия приходят с потоков libwebrtc.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        pairStore = EncryptedPairStore(this)
        journal = SessionJournal(this)
        overlay = SessionOverlay(this)

        // Режим записи сценария — противоположность показу: экран НЕ снимаем, только
        // действия. Поэтому на входе в запись глушим любой активный показ и вешаем красный
        // индикатор записи; на выходе — снимаем. Всё через главный поток: колбэк прилетает
        // из потока службы доступности.
        ru.pult.grandma.control.ScenarioRecorder.shared.onStateChange = { recording ->
            mainHandler.post { if (recording) enterRecordingMode() else exitRecordingMode() }
        }
        // Держим Wi-Fi и CPU живыми, иначе на MIUI при выключенном экране сокет умирает
        // и запрос помощи до бабушки не доходит (docs/android-grandma.md §3).
        keepAlive = KeepAlive(this).also { it.acquire() }

        // Только dataSync. Тип mediaProjection здесь запрашивать нельзя: система требует
        // уже выданного разрешения на захват, которого при старте нет и быть не должно —
        // сервис поднимается задолго до того, как бабушка что-то разрешит.
        promoteToMediaProjection(false)
        // Два сторожа: WorkManager (пережидает Doze) и AlarmManager (точный, пробивает Doze
        // на агрессивных прошивках). Плюс отметка «жив» — по разрывам видно смерти сервиса.
        ServiceWatchdog.schedule(this)
        ServiceHeartbeat.schedule(this)
        ServiceHeartbeat.Deaths.markAlive(this, "onCreate")

        pairStore.load()?.let(::connect)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_GRANT -> onConsentGranted(intent)
            ACTION_DENY -> controller?.deny()
            ACTION_STOP_SESSION -> stopSession()
            ACTION_PAIR_CHANGED -> reconnect()
        }
        // START_STICKY: система обязана поднять сервис обратно, если убила его под нагрузкой.
        return START_STICKY
    }

    private fun connect(pair: PairRecord) {
        peerName = pair.peerName
        // Список входов: адрес пары + запасные (другие домены/CDN). РКН режет known-адреса,
        // поэтому один заблокированный вход не должен отрезать бабушку — клиент переберёт
        // остальные. Список пополняется из подписанного источника (EndpointStore).
        val endpoints = EndpointStore(this).endpoints(primary = pair.signalingUrl)
        val client = SignalingClient(
            endpoints = endpoints,
            identity = SignalingClient.Identity(
                pairId = pair.pairId,
                role = Role.GRANDMA,
                deviceId = deviceId(),
                journalTokenHash = pair.journalTokenHash,
            ),
            scope = lifecycleScope,
        )
        signaling = client

        val session = SessionController(
            pair = pair,
            signaling = client,
            transport = WebRtcScreenTransport(this),
            journal = journal,
            scope = lifecycleScope,
        )
        session.onControlMessage = ::onControlMessage
        session.onCaptureStarting = { promoteToMediaProjection(true) }
        // Турнкей-режим («поставили и забыли»): бабушка не совершает действий.
        // Видимость при этом сохраняется полностью — это отдельная, неотключаемая вещь.
        session.autoAccept = PultApp.prefs(this).getBoolean(PREF_AUTO_ACCEPT, false)
        controller = session

        lifecycleScope.launch {
            client.incoming.collect { signal ->
                // Отзыв доступа обрабатываем на уровне сервиса: он трогает хранилище пары,
                // а не сессию. Всё остальное уходит в SessionController.
                if (signal is Signal.Revoke) onRevoke(pair, signal) else session.handle(signal)
            }
        }
        // Как только связь есть — отдаём серверу FCM-токен, чтобы он мог будить этот телефон
        // push-ом, когда прошивка убьёт сервис (главный механизм надёжности на MIUI).
        lifecycleScope.launch {
            client.link.collect { link ->
                if (link == SignalingClient.Link.ONLINE) {
                    PultMessagingService.token(this@PultService)?.let {
                        client.send(Signal.RegisterPush(it))
                    }
                }
            }
        }
        lifecycleScope.launch {
            client.link.collect { link ->
                notify(Notifications.status(this@PultService, peerName, link == SignalingClient.Link.ONLINE))
            }
        }
        lifecycleScope.launch {
            session.ui.collectLatest(::renderUi)
        }
        // Подстраховка переподключения: если помощник пропал из сети во время сессии,
        // завершаем её локально. Иначе телефон застрял бы в CONNECTED и игнорировал
        // следующий запрос помощи (баг «внук ушёл со страницы — связь больше не работает»).
        lifecycleScope.launch {
            client.peerOnline.collect { online -> if (!online) session.onPeerLost() }
        }

        client.connect()
    }

    private fun renderUi(state: SessionController.Ui) {
        when (state) {
            is SessionController.Ui.Idle -> {
                overlay?.hide()
                foregroundJob?.cancel()
                cancel(Notifications.ID_SESSION)
                // Проекция переживает сессию (на MIUI её из фона не пересоздать), поэтому
                // тип FGS остаётся mediaProjection, пока захват жив: Android 14+ требует
                // именно этот тип, пока проекция активна. Демоутим только когда захвата нет.
                if (controller?.captureReady != true) promoteToMediaProjection(false)
            }

            is SessionController.Ui.Asked ->
                // Турнкей + проекция уже поднята → поднимаем показ без системного диалога:
                // из фона на MIUI его всё равно не открыть, а именно так ломалась вторая
                // сессия. Видимость сохраняют рамка и уведомление. В обычном (не-турнкей)
                // режиме согласие спрашивается каждый раз — как и должно быть в продукте.
                if (state.auto && controller?.captureReady == true) {
                    controller?.grant()
                } else {
                    ConsentActivity.show(this, state.peerName, state.note, auto = state.auto)
                }

            is SessionController.Ui.Session -> {
                // Тип mediaProjection уже включён в onCaptureStarting — до создания проекции.
                // Рамка, подпись и «Стоп» — всё время сессии, без вариантов их убрать.
                overlay?.show(state.peerName, onStop = ::stopSession)
                notify(Notifications.session(this, state.peerName), Notifications.ID_SESSION)
                watchForegroundApp()
                // Сообщаем помощнику, доступно ли управление (включена ли служба), как только
                // откроется data-канал — панель сразу покажет верный переключатель.
                mainHandler.postDelayed({ reportControlState() }, 1500)
                // Уходим с глаз долой: делиться и управлять надо НАСТОЯЩИМ экраном бабушки,
                // а не окном самого приложения. Роняем себя на рабочий стол — помощник видит
                // реальный экран, а нижние кнопки навигации снова работают (оверлей сквозной).
                stepAside()
            }
        }
    }

    /**
     * Бабушка нажала «Разрешить», и система вернула разрешение на захват.
     * Порядок именно такой: сначала понятный вопрос от нас, потом системный диалог.
     */
    private fun onConsentGranted(intent: Intent) {
        val controller = controller ?: return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            // Захват не разрешён (или отменён системой) — сессии не будет.
            controller.deny()
            return
        }

        // Порядок важен: сначала кладём разрешение на захват, потом даём согласие.
        // Иначе рукопожатие WebRTC успеет дойти до auth-confirm раньше, чем появится
        // проекция, и показ сорвётся ошибкой (эта гонка ловилась в турнкей-режиме).
        controller.screenPermission = data
        controller.grant()
    }

    /**
     * Тип foreground-сервиса: `mediaProjection` живёт ровно столько, сколько идёт показ.
     * Постоянный `mediaProjection` — и лишний повод для отказа в Play, и лишний расход.
     */
    private fun promoteToMediaProjection(active: Boolean) {
        val connectedNow = signaling?.link?.value == SignalingClient.Link.ONLINE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_STATUS, Notifications.status(this, peerName, connectedNow))
            return
        }
        val type = if (active) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(Notifications.ID_STATUS, Notifications.status(this, peerName, connectedNow), type)
    }

    /**
     * Управление от помощника по data-каналу: указатель, подписи, а также реальные
     * тап/свайп/навигация — их выполняет служба доступности (RemoteControlService).
     * Координаты приходят в долях кадра (0..1); переводим в пиксели экрана бабушки.
     * Если служба не включена — управление недоступно, но показ и указатель работают.
     *
     * ВАЖНО: этот колбэк приходит с signaling-потока libwebrtc, а не с главного.
     * Любое обращение к View оверлея обязано уйти на главный поток — иначе исключение
     * «wrong thread» всплывёт в нативном JNI-колбэке и уронит процесс через SIGABRT.
     */
    private fun onControlMessage(payload: String) {
        android.util.Log.i("PultControl", "recv: $payload")
        val message = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        fun frac(k: String) = message[k]?.jsonPrimitive?.float
        when (message["t"]?.jsonPrimitive?.content) {
            "pointer" -> {
                val x = frac("x") ?: return
                val y = frac("y") ?: return
                mainHandler.post { overlay?.movePointer(x, y) }
            }

            // Реальный тап: показываем указатель И нажимаем за бабушку.
            "tap" -> {
                val x = frac("x") ?: return
                val y = frac("y") ?: return
                mainHandler.post { overlay?.movePointer(x, y) }
                val (w, h) = displaySize()
                RemoteControlService.instance?.tap(x * w, y * h)
            }

            "swipe" -> {
                val x1 = frac("x1") ?: return
                val y1 = frac("y1") ?: return
                val x2 = frac("x2") ?: return
                val y2 = frac("y2") ?: return
                val ms = message["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 250L
                val (w, h) = displaySize()
                RemoteControlService.instance?.swipe(x1 * w, y1 * h, x2 * w, y2 * h, ms)
            }

            "nav" -> message["action"]?.jsonPrimitive?.content?.let { action ->
                RemoteControlService.instance?.nav(action)
            }

            // Громкость, яркость, шторка, быстрые настройки, блокировка.
            "sys" -> message["action"]?.jsonPrimitive?.content?.let { action ->
                RemoteControlService.instance?.system(action)
            }

            // Удалённое вкл/выкл службы управления. Выкл — сама себя (disableSelf),
            // полностью удалённо: так внук гасит управление после помощи, и банк оживает.
            // Вкл — только открыть настройки доступности: включить может лишь пользователь.
            "control" -> {
                val on = message["on"]?.jsonPrimitive?.content == "true"
                mainHandler.post { setControlServiceEnabled(on) }
            }

            // Открыть ссылку напрямую: Google Photos, YouTube, WhatsApp-чат и т.п. Самый
            // надёжный путь — без блужданий по меню и без пиксельных тапов.
            "open" -> message["url"]?.jsonPrimitive?.content?.let { url ->
                mainHandler.post { openLink(url) }
            }

            // Переход по элементам интерфейса: фокус на следующий/предыдущий кликабельный
            // элемент и его активация. Устойчивее тапа по координатам.
            "focus" -> message["dir"]?.jsonPrimitive?.content?.let { dir ->
                RemoteControlService.instance?.focus(dir)
            }

            "stop" -> mainHandler.post { stopSession() }
        }
    }

    /** Реальный размер дисплея в пикселях — для перевода долей кадра в координаты жеста. */
    private fun displaySize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Гашение показа на банковских приложениях. Опрос идёт только во время сессии
     * и только на стороне бабушки — отключить его помощник не может.
     */
    private fun watchForegroundApp() {
        if (foregroundJob?.isActive == true) return
        val watcher = ForegroundAppWatcher(this)
        if (!watcher.hasPermission()) return

        foregroundJob = lifecycleScope.launch {
            var redacted = false
            while (isActive) {
                val shouldRedact = watcher.shouldRedact()
                if (shouldRedact != redacted) {
                    redacted = shouldRedact
                    controller?.setRedacted(shouldRedact)
                }
                delay(ForegroundAppWatcher.POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopSession() {
        controller?.stop()
        overlay?.hide()
        cancel(Notifications.ID_SESSION)
    }

    /**
     * Удалённое вкл/выкл службы управления.
     *
     * ВЫКЛ — служба гасит себя сама (`disableSelf`): полностью удалённо, мгновенно. Именно
     * так внук снимает управление после помощи, чтобы банк (Nordea/Сбер) снова заработал.
     *
     * ВКЛ — приложение НЕ может включить свою accessibility-службу само (нужен системный
     * уровень). Поэтому открываем экран настроек доступности: включает человек одним тапом.
     * На телефоне «под ключ» (Device Owner) это можно будет автоматизировать (§116).
     *
     * После — сообщаем помощнику фактическое состояние службы, чтобы панель его показала.
     */
    private fun setControlServiceEnabled(on: Boolean) {
        android.util.Log.i("PultControl", "setControlServiceEnabled(on=$on) instance=${RemoteControlService.instance}")
        if (on) {
            if (RemoteControlService.instance == null) {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            RemoteControlService.instance?.disableSelf()
        }
        // disableSelf асинхронна — состояние сверяем с задержкой, отражая фактическое.
        mainHandler.postDelayed({ reportControlState() }, 700)
    }

    /**
     * Открыть ссылку на телефоне бабушки. Работает и для веб-ссылок (Google Photos, YouTube),
     * и для диплинков приложений (whatsapp://, tg://). Приложение само решит, чем открыть.
     */
    private fun openLink(url: String) {
        val uri = runCatching { android.net.Uri.parse(url.trim()) }.getOrNull() ?: return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Сообщить помощнику, включена ли служба управления сейчас. */
    private fun reportControlState() {
        val enabled = RemoteControlService.instance != null
        controller?.sendControl("""{"t":"control-state","enabled":$enabled}""")
    }

    /**
     * Вход в режим записи сценария. Захват экрана здесь неуместен: мы не снимаем пиксели,
     * а записываем действия. Поэтому останавливаем любой идущий показ и переключаем оверлей
     * на красный индикатор записи — бабушка видит, что идёт запись, а не трансляция.
     */
    private fun enterRecordingMode() {
        if (controller?.ui?.value !is SessionController.Ui.Idle) {
            controller?.stop()
            cancel(Notifications.ID_SESSION)
        }
        // Полностью отпускаем проекцию: в записи ничего не снимаем, и это должно быть правдой,
        // а не «поставили на паузу». Следующий показ возьмёт разрешение заново.
        controller?.releaseCapture()
        promoteToMediaProjection(false)
        overlay?.hide()
        overlay?.showRecording()
    }

    private fun exitRecordingMode() {
        overlay?.hide()
    }

    /**
     * Роняем приложение на рабочий стол в момент старта показа. `MediaProjection`
     * захватывает весь дисплей независимо от того, какое окно наверху, — поэтому помощник
     * начинает видеть настоящий экран бабушки, а не наше окно «Всё работает». Сквозной
     * оверлей (рамка + указатель) и уведомление «Стоп» остаются поверх: видимость сохраняется.
     */
    private fun stepAside() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(home) }
    }

    /**
     * Внук отозвал доступ. Подпись проверяем локально секретом пары — сервер её подделать
     * не может. Сошлось → стираем секрет и разрываем пару: после этого ни одно подключение
     * не пройдёт проверку. Это и есть «забыть помощника», только инициированное со стороны внука.
     */
    private fun onRevoke(pair: PairRecord, signal: Signal.Revoke) {
        val expected = PairAuth.revokeMac(pair.secret, pair.pairId, signal.nonce)
        if (!PairAuth.verify(expected, signal.mac)) {
            // Подделка (или сбойный сервер) — игнорируем, пара остаётся.
            return
        }
        controller?.stop()
        signaling?.send(Signal.RevokeAck(ok = true))
        // Даём ack уйти, потом рвём всё.
        mainHandler.postDelayed({
            pairStore.clear()
            peerName = null
            signaling?.close()
            signaling = null
            controller = null
            notify(Notifications.status(this, null, connected = false))
        }, 300)
    }

    private fun reconnect() {
        signaling?.close()
        controller = null
        pairStore.load()?.let(::connect)
    }

    /**
     * Бабушка (или прошивка) смахнула приложение из недавних. Для сервисного приложения
     * это не повод умирать — немедленно планируем подъём. На MIUI это одна из главных
     * причин смерти, поэтому фиксируем её в журнале.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceHeartbeat.Deaths.markDeath(this, "task-removed")
        ServiceHeartbeat.schedule(this)
        val restart = PendingIntent.getForegroundService(
            this,
            2,
            Intent(this, PultService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(android.app.AlarmManager::class.java)?.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restart,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceHeartbeat.Deaths.markDeath(this, "onDestroy")
        // Проекция живёт между сессиями, но не должна пережить сам сервис.
        controller?.releaseCapture()
        signaling?.close()
        overlay?.hide()
        keepAlive.release()
        super.onDestroy()
    }

    private fun notify(notification: android.app.Notification, id: Int = Notifications.ID_STATUS) {
        getSystemService(android.app.NotificationManager::class.java).notify(id, notification)
    }

    private fun cancel(id: Int) {
        getSystemService(android.app.NotificationManager::class.java).cancel(id)
    }

    /** Идентификатор устройства — случайный и локальный; ни с чем внешним не связан. */
    private fun deviceId(): String {
        val prefs = getSharedPreferences("pult_device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: PairAuth.newId(8).also {
            prefs.edit().putString("id", it).apply()
        }
    }

    companion object {
        /** Настройка «под ключ»: включается семьёй при настройке (в бою — Device Owner). */
        const val PREF_AUTO_ACCEPT = "auto_accept"

        const val ACTION_GRANT = "ru.pult.grandma.GRANT"
        const val ACTION_DENY = "ru.pult.grandma.DENY"
        const val ACTION_STOP_SESSION = "ru.pult.grandma.STOP_SESSION"
        const val ACTION_PAIR_CHANGED = "ru.pult.grandma.PAIR_CHANGED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PultService::class.java))
        }

        fun grant(context: Context, resultCode: Int, data: Intent?) {
            context.startService(
                Intent(context, PultService::class.java)
                    .setAction(ACTION_GRANT)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun deny(context: Context) {
            context.startService(Intent(context, PultService::class.java).setAction(ACTION_DENY))
        }

        fun pairChanged(context: Context) {
            context.startService(
                Intent(context, PultService::class.java).setAction(ACTION_PAIR_CHANGED),
            )
        }
    }
}
