package ru.pult.grandma.service

import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
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
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.PultApp
import ru.pult.grandma.session.ForegroundAppWatcher
import android.view.WindowManager
import ru.pult.grandma.control.RemoteControlService
import ru.pult.grandma.net.ConfigRefresher
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
    private var tunnel: ru.pult.core.tunnel.TunnelEgress? = null
    private var foregroundJob: Job? = null
    private var peerName: String? = null
    private lateinit var keepAlive: KeepAlive

    // Сохранённое состояние автоповорота экрана — восстанавливаем после сессии.
    private var rotationWasLocked: Int? = null
    private var rotationWasUser: Int? = null

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

        // Автоподхват адресов/ключей из подписанного источника — ДО подключения, чтобы
        // связаться уже по актуальному списку входов. Применяется, только если подпись сходится.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ConfigRefresher(this@PultService).refresh() }
        }

        pairStore.load()?.let(::connect)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_GRANT -> onConsentGranted(intent)
            ACTION_DENY -> controller?.deny()
            ACTION_STOP_SESSION -> stopSession()
            ACTION_PAIR_CHANGED -> reconnect()
            ACTION_PAIR_ADB -> {
                // Паринг wireless ADB: с явными port/code — напрямую; без аргументов —
                // авто-паринг: открываем системный диалог и читаем код своей a11y-службой.
                val port = intent.getIntExtra("port", 0)
                val code = intent.getStringExtra("code").orEmpty()
                Thread {
                    if (port > 0 && code.isNotEmpty()) {
                        val ok = ru.pult.grandma.control.AdbShell.pair(port, code)
                        android.util.Log.i("PultAdb", "pair result: $ok (port=$port)")
                        if (ok) {
                            PultApp.prefs(this).edit()
                                .putLong("pair_ok_at", System.currentTimeMillis()).apply()
                        }
                    } else {
                        val (ok, detail) = ru.pult.grandma.control.AdbShell.autoPair(this)
                        android.util.Log.i("PultAdb", "autoPair result: $ok ($detail)")
                        if (ok) {
                            PultApp.prefs(this).edit()
                                .putLong("pair_ok_at", System.currentTimeMillis()).apply()
                        }
                    }
                }.start()
            }
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
        // В отладке владелец подключается к своему устройству — согласие по умолчанию.
        session.autoAccept = PultApp.prefs(this).getBoolean(PREF_AUTO_ACCEPT, BuildConfig.DEBUG)
        controller = session

        lifecycleScope.launch {
            client.incoming.collect { signal ->
                // Отзыв доступа обрабатываем на уровне сервиса: он трогает хранилище пары,
                // а не сессию. Всё остальное уходит в SessionController.
                when (signal) {
                    is Signal.Revoke -> onRevoke(pair, signal)
                    is Signal.Deeplink -> onDeeplink(client, signal)
                    is Signal.Screencast -> onScreencast(pair, signal)
                    else -> session.handle(signal)
                }
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

        // Wireless debugging включаем сами: после ребута прошивка её сбрасывает,
        // а без неё наш ADB-канал (LanAgent-замена) не поднять. Разрешение выдано
        // при настройке устройства (WRITE_SECURE_SETTINGS).
        runCatching {
            android.provider.Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
        }

        // TCP-туннель для приложения шлюза (Swedbank выходит с IP этого телефона).
        // Токен зашит при сборке (личный демо-режим); пустой — туннель выключен.
        if (BuildConfig.TUNNEL_TOKEN.isNotBlank()) {
            // Токен и pairId — в query, поэтому кодируем («+» в токене иначе станет пробелом).
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            val tunnelUrl = pair.signalingUrl.removeSuffix("/ws") +
                "/tunnel?pairId=${enc(pair.pairId)}&token=${enc(BuildConfig.TUNNEL_TOKEN)}&side=phone"
            tunnel?.stop()
            tunnel = ru.pult.core.tunnel.TunnelEgress(tunnelUrl).also { it.start() }
        }
    }

    private fun renderUi(state: SessionController.Ui) {
        when (state) {
            is SessionController.Ui.Idle -> {
                overlay?.hide()
                foregroundJob?.cancel()
                cancel(Notifications.ID_SESSION)
                restoreRotation()
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
                lockRotationPortrait()
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

            // Обучение: слайд-инструкция приходит чанками (картинка в base64), затем показ.
            "slide" -> {
                val id = message["id"]?.jsonPrimitive?.content ?: return
                val data = message["data"]?.jsonPrimitive?.content ?: return
                slideBuffer.getOrPut(id) { StringBuilder() }.append(data)
            }
            "slide-done" -> {
                val id = message["id"]?.jsonPrimitive?.content ?: return
                val caption = message["caption"]?.jsonPrimitive?.content
                val b64 = slideBuffer.remove(id)?.toString() ?: return
                val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() ?: return
                val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
                mainHandler.post { overlay?.showSlide(bmp, caption) }
            }
            "slide-hide" -> mainHandler.post { overlay?.hideSlide() }

            // Дерево UI для записи сценариев: панель показывает элементы кликабельными,
            // даже когда видео заблокировано (FLAG_SECURE). Ответ уходит тем же каналом.
            "dump-ui" -> {
                val tree = RemoteControlService.dumpTreeJson()
                android.util.Log.i("PultControl", "dump-ui: tree=${tree?.take(200)}")
                controller?.sendControl("""{"t":"ui-tree","tree":${tree ?: "[]"}}""")
                android.util.Log.i("PultControl", "dump-ui: sent")
            }

            // Запись сценария с панели: тапы по дереву UI записываются как шаги.
            "start-recording" -> mainHandler.post {
                ru.pult.grandma.control.ScenarioRecorder.shared.start()
                notify(Notifications.status(this@PultService, peerName, connected = true))
            }
            "stop-recording" -> mainHandler.post {
                val steps = ru.pult.grandma.control.ScenarioRecorder.shared.stop()
                val name = message["name"]?.jsonPrimitive?.content ?: "Сценарий ${steps.size} шагов"
                val scenario = ru.pult.grandma.control.Scenario(
                    id = java.util.UUID.randomUUID().toString().take(8),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    steps = steps,
                )
                ru.pult.grandma.control.ScenarioStore(filesDir).add(scenario)
                notify(Notifications.status(this@PultService, peerName, connected = true))
            }

            // Сохранить сценарий, записанный в панели (эмулятор PIN BankID).
            // Шаги приходят готовыми с координатами — телефон только сохраняет.
            "save-scenario" -> {
                val name = message["name"]?.jsonPrimitive?.content ?: return
                val stepsJson = message["steps"] ?: return
                mainHandler.post {
                    runCatching {
                        val steps = kotlinx.serialization.json.Json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(ru.pult.grandma.control.Step.serializer()),
                            stepsJson,
                        )
                        val scenario = ru.pult.grandma.control.Scenario(
                            id = java.util.UUID.randomUUID().toString().take(8),
                            name = name,
                            createdAt = System.currentTimeMillis(),
                            steps = steps,
                        )
                        ru.pult.grandma.control.ScenarioStore(filesDir).add(scenario)
                        android.util.Log.i("PultControl", "scenario saved from panel: $name (${steps.size} steps)")
                    }.onFailure {
                        android.util.Log.e("PultControl", "save-scenario failed: ${it.message}")
                    }
                }
            }

            // Открыть эмулятор BankID на телефоне: панель вызывает копию экрана,
            // тапает по ней, шаги пишутся. Потом тот же сценарий идёт на живой BankID.
            "open-emulator" -> mainHandler.post {
                runCatching {
                    startActivity(
                        android.content.Intent(this@PultService, ru.pult.grandma.ui.BankIdEmulatorActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            // Воспроизвести сценарий на живом приложении: открыть его и повторить шаги.
            "replay-scenario" -> {
                val name = message["name"]?.jsonPrimitive?.content ?: return
                mainHandler.post {
                    val scenario = ru.pult.grandma.control.ScenarioStore(filesDir).load()
                        .firstOrNull { it.name == name || it.id == name }
                    if (scenario == null) {
                        android.util.Log.w("PultControl", "scenario not found: $name")
                        return@post
                    }
                    // Открыть целевое приложение, затем воспроизвести шаги.
                    val pkg = scenario.steps.firstOrNull { it.expectPackage != null }?.expectPackage
                    pkg?.let { p ->
                        runCatching {
                            packageManager.getLaunchIntentForPackage(p)?.let { intent ->
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            }
                        }
                    }
                    // Шаги воспроизводит ScenarioPlayer через службу доступности.
                    // play() спит между шагами — гоним в фоне, чтобы не вешать главный поток.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Thread {
                            val result = RemoteControlService.instance?.player?.play(scenario)
                            android.util.Log.i("PultControl", "replay result: $result")
                        }.start()
                    }, 2000)
                    android.util.Log.i("PultControl", "replaying scenario: ${scenario.name} (${scenario.steps.size} steps)")
                }
            }

            "stop" -> mainHandler.post { stopSession() }
        }
    }

    /** Сборка приходящих чанков слайда по id (обучающий режим). */
    private val slideBuffer = HashMap<String, StringBuilder>()

    /** Реальный размер дисплея в пикселях — для перевода долей кадра в координаты жеста. */
    private fun displaySize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Блокировка автоповорота экрана в портрет на время сессии.
     * Требует WRITE_SETTINGS; если разрешения нет — просто не крутим экран.
     * Восстанавливаем предыдущее состояние в `restoreRotation()`.
     */
    private fun lockRotationPortrait() {
        if (!android.provider.Settings.System.canWrite(this)) return
        runCatching {
            val resolver = contentResolver
            rotationWasLocked = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.ACCELEROMETER_ROTATION)
            rotationWasUser = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.USER_ROTATION)
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.USER_ROTATION, android.view.Surface.ROTATION_0)
            android.util.Log.i("PultControl", "rotation locked to portrait")
        }
    }

    private fun restoreRotation() {
        if (!android.provider.Settings.System.canWrite(this)) return
        val locked = rotationWasLocked ?: return
        val user = rotationWasUser ?: android.view.Surface.ROTATION_0
        runCatching {
            val resolver = contentResolver
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, locked)
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.USER_ROTATION, user)
            android.util.Log.i("PultControl", "rotation restored")
        }
        rotationWasLocked = null
        rotationWasUser = null
    }

    /**
     * Гашение показа на банковских приложениях и отключение accessibility-службы,
     * когда на экране BankID/банк. Опрос идёт только во время сессии
     * и только на стороне бабушки — отключить его помощник не может.
     */
    private fun watchForegroundApp() {
        if (foregroundJob?.isActive == true) return
        val watcher = ForegroundAppWatcher(this)
        if (!watcher.hasPermission()) return

        foregroundJob = lifecycleScope.launch {
            var redacted = false
            var serviceDisabledForBanking = false
            while (isActive) {
                val shouldRedact = watcher.shouldRedact()
                if (shouldRedact != redacted) {
                    redacted = shouldRedact
                    controller?.setRedacted(shouldRedact, watcher.lastPackage)
                    // BankID отслеживает foreground-сервис типа mediaProjection. На время
                    // гашения переводим сервис в dataSync: проекция жива, но система и
                    // банки не считают захват активным. При снятии гашения возвращаем тип.
                    if (controller?.captureReady == true) {
                        promoteToMediaProjection(!shouldRedact)
                    }
                }
                // BankID и ряд банковских приложений отказываются работать,
                // пока включена сторонняя служба доступности. Отключаем её
                // автоматически, как только на экране появляется банковское приложение.
                if (redacted && !serviceDisabledForBanking) {
                    if (RemoteControlService.instance != null) {
                        android.util.Log.i("PultControl", "banking app on screen — disabling accessibility service")
                        setControlServiceEnabled(false)
                        serviceDisabledForBanking = true
                    }
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

    /**
     * Диплинк от шлюз-приложения (канал /link): открываем BankID и сами завершаем вход —
     * без панели и без WebRTC-сессии. Тачи идут через scrcpy-инъекцию (BankIdAgent),
     * PIN берём из настроек (задаётся один раз на телефоне, никуда не уходит).
     */
    private fun onDeeplink(client: SignalingClient, signal: Signal.Deeplink) {
        val url = signal.url
        if (!url.startsWith("bankid:///")) {
            client.send(Signal.DeeplinkStatus(ok = false, stage = "failed", err = "not-a-bankid-url"))
            return
        }
        val pin = PultApp.prefs(this).getString(PREF_BANKID_PIN, "") ?: ""
        if (pin.isEmpty()) {
            client.send(Signal.DeeplinkStatus(ok = false, stage = "failed", err = "no-pin-on-phone"))
            return
        }
        val lockPin = PultApp.prefs(this).getString(PREF_LOCK_PIN, "") ?: ""
        // Открываем через LanAgent (shell UID): startActivity из фонового сервиса
        // на Android 14+ блокируется BAL. Запрос к агенту — TCP, сеть недоступна
        // главному потоку не нужна: весь обработчик короткий, но сокет — да,
        // поэтому весь блок — в фоне.
        Thread {
            if (!ru.pult.grandma.control.BankIdAgent.openDeeplink(url)) {
                client.send(Signal.DeeplinkStatus(ok = false, stage = "failed", err = "bankid-open-failed"))
                return@Thread
            }
            client.send(Signal.DeeplinkStatus(ok = true, stage = "opened"))
            android.util.Log.i("PultControl", "deeplink opened, completing BankID locally")
            // Долгая последовательность (десятки секунд) — в этом же фоновом потоке.
            val err = ru.pult.grandma.control.BankIdAgent.complete(pin, lockPin)
            runCatching {
                client.send(
                    if (err == null) Signal.DeeplinkStatus(ok = true, stage = "signed")
                    else Signal.DeeplinkStatus(ok = false, stage = "failed", err = err.take(120)),
                )
            }
        }.start()
    }

    /**
     * Показ экрана по запросу приложения шлюза (канал /link): старт/стоп lowlat-трансляции.
     * Запуск через LanAgent (shell): из фона startActivity заблокирован (BAL), а согласие
     * на захват автоматом даёт appops PROJECT_MEDIA=allow (выставляется при настройке).
     */
    private fun onScreencast(pair: PairRecord, signal: Signal.Screencast) {
        Thread {
            val cmd = if (signal.on) {
                val url = pair.signalingUrl.removeSuffix("/ws") + "/lowlat?room=demo&role=device"
                "am start -n se.pult.app/ru.pult.grandma.lowlat.LowLatActivity" +
                    " --ei bitrate 1500000 --ei fps 20 --es url '$url'"
            } else {
                "am stopservice -n se.pult.app/ru.pult.grandma.lowlat.LowLatService"
            }
            val (ok, out) = ru.pult.grandma.control.BankIdAgent.shell(cmd)
            android.util.Log.i("PultControl", "screencast on=${signal.on} ok=$ok ${out.take(100)}")
        }.start()
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
        tunnel?.stop()
        tunnel = null
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
        private const val TAG = "PultService"

        /** Настройка «под ключ»: включается семьёй при настройке (в бою — Device Owner). */
        const val PREF_AUTO_ACCEPT = "auto_accept"

        /** PIN BankID для локального завершения входа по диплинку (v2-шлюз). */
        const val PREF_BANKID_PIN = "bankid_pin"

        /** Код экрана блокировки headless-устройства: разблокировать перед BankID. */
        const val PREF_LOCK_PIN = "lock_pin"

        const val ACTION_GRANT = "ru.pult.grandma.GRANT"
        const val ACTION_DENY = "ru.pult.grandma.DENY"
        const val ACTION_STOP_SESSION = "ru.pult.grandma.STOP_SESSION"
        const val ACTION_PAIR_CHANGED = "ru.pult.grandma.PAIR_CHANGED"
        const val ACTION_PAIR_ADB = "ru.pult.grandma.PAIR_ADB"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context) {
            // API 31+: система запрещает старт FGS, когда процесс подняли в фоне
            // (WorkManager, alarm-рессейдж, boot на части прошивок). Падать нельзя: сторожа
            // поднимают процесс именно в фоне, и исключение в Application.onCreate превращалось
            // в crash-loop — «crashed too many times, killing!», после чего сервис мёртв до
            // ручного запуска. Пропускаем старт: поднимет следующее легальное окно —
            // FCM high-priority, загрузка устройства или открытие приложения.
            try {
                context.startForegroundService(Intent(context, PultService::class.java))
            } catch (notAllowed: ForegroundServiceStartNotAllowedException) {
                android.util.Log.w(TAG, "старт FGS из фона запрещён системой — ждём легальное окно")
            }
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
