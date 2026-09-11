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
import java.net.NetworkInterface
import ru.pult.grandma.control.BankIdMode
import ru.pult.grandma.control.RemoteControlService
import ru.pult.grandma.net.ConfigRefresher
import ru.pult.grandma.net.EndpointStore
import ru.pult.grandma.net.UpdateManager
import ru.pult.grandma.session.QrWatcher
import ru.pult.grandma.session.SessionController
import ru.pult.grandma.session.SessionJournal
import ru.pult.grandma.push.PultMessagingService
import ru.pult.grandma.session.WebRtcScreenTransport
import ru.pult.grandma.updatable.ModuleRegistry
import ru.pult.grandma.ui.BankIdPinActivity
import ru.pult.grandma.ui.ConsentActivity
import ru.pult.grandma.pin.PinStorage

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
    private var signaling: SignalingClient? = null
    private var controller: SessionController? = null
    private var tunnel: ru.pult.core.tunnel.TunnelEgress? = null
    private var foregroundJob: Job? = null
    private lateinit var keepAlive: KeepAlive
    private lateinit var updates: UpdateManager
    private lateinit var qrWatcher: QrWatcher

    // Сохранённое состояние автоповорота экрана — восстанавливаем после сессии.
    private var rotationWasLocked: Int? = null
    private var rotationWasUser: Int? = null

    // Дедупликация bankid-диплинков: повтор той же ссылки в течение 30 с не поднимаем.
    // lastDeeplinkOk — флаг, что прошлый подъём РЕАЛЬНО удался: дедуплицировать
    // провал нельзя (Б получит ложное «opened» и не перепробует вход).
    private var lastDeeplinkUrl: String? = null
    private var lastDeeplinkAt = 0L
    private var lastDeeplinkOk = false

    // Мост на главный поток: колбэки datachannel и присутствия приходят с потоков libwebrtc.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Смена сети (Wi-Fi ↔ LTE): сокет над умершей сетью молчит до таймаута, а backoff
    // к этому моменту уже вырос — просим SignalingClient переподключаться немедленно.
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            signaling?.forceReconnectNow()
        }

        override fun onLost(network: android.net.Network) {
            signaling?.forceReconnectNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        pairStore = EncryptedPairStore(this)
        journal = SessionJournal(this)

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
        // Самодиагностика фоновых разрешений: что прошивка отобрала — видно по журналу.
        BootDiagnostics.run(this)
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
        // Периодическая проверка обновлений (apk/dex) — дополняет мгновенный пуш.
        UpdateCheckWorker.schedule(this)
        // Full-screen intent подъёма BankID требует POST_NOTIFICATIONS (Android 13+):
        // без разрешения FSI молча пропускается и диплинк падает с bankid-open-failed
        // (телефон спит запертым — других путей подъёма в этот момент нет).
        maybeAskNotificationPermission()

        updates = UpdateManager(this)
        // QR с экрана BankID (удалённый вход) — помощнику по control-каналу.
        qrWatcher = QrWatcher(this) { payload -> controller?.sendControl(payload) }
        // Режим BankID (удалённый/местный вход) — помощнику, если канал открыт.
        BankIdMode.onModeChange = { mode ->
            val name = if (mode == BankIdMode.Mode.REMOTE) "remote" else "local"
            controller?.sendControl("""{"t":"bankid-mode","mode":"$name"}""")
        }

        // Автоподхват адресов/ключей из подписанного источника — ДО подключения, чтобы
        // связаться уже по актуальному списку входов. Применяется, только если подпись сходится.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ConfigRefresher(this@PultService).refresh() }
            // dex-модуль, применённый до перезапуска, поднимаем обратно (sha сверяется).
            runCatching { updates.loadPersistedModule() }
        }

        pairStore.load()?.let(::connect)
    }

    /**
     * Разрешение на уведомления нужно для full-screen intent подъёма BankID (Android 13+).
     * Просим редко: максимум раз в сутки, пока не выдано. Отказ — не ошибка: просто
     * FSI-путь остаётся недоступен, как и был.
     */
    private fun maybeAskNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = PultApp.prefs(this)
        val lastAsk = prefs.getLong("notif_perm_asked_at", 0L)
        if (System.currentTimeMillis() - lastAsk < 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong("notif_perm_asked_at", System.currentTimeMillis()).apply()
        runCatching {
            startActivity(
                Intent(this, ru.pult.grandma.ui.PermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Защита exact-будильника: после ребута/глубокого Doze планировщик мог его потерять —
        // каждый легальный старт сервиса перевзводит оба сторожа.
        ServiceHeartbeat.schedule(this)
        when (intent?.action) {
            ACTION_GRANT -> onConsentGranted(intent)
            ACTION_DENY -> controller?.deny()
            ACTION_STOP_SESSION -> stopSession()
            ACTION_PAIR_CHANGED -> reconnect()
            // Удалённая реанимация: сигналинг застрял, но процесс жив (FCM дошёл).
            ACTION_REANIMATE_RESTART -> reconnect()
            ACTION_TEST_LOGIN -> {
                // Локальный диплинк (pult://bankid-login?url=...): та же цепочка, что от
                // шлюза — без сервера и без клиента, статусы только в лог.
                intent?.getStringExtra(EXTRA_URL)?.let { handleDeeplink(null, it) }
            }
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
                label = deviceLabel(),
                model = Build.MODEL.take(60),
                os = "Android ${Build.VERSION.RELEASE}",
                ip = localIp(),
            ),
            scope = lifecycleScope,
        )
        signaling = client

        val transport = WebRtcScreenTransport(this).also {
            it.setFrameListener(qrWatcher::onFrame)
        }
        val session = SessionController(
            pair = pair,
            signaling = client,
            transport = transport,
            journal = journal,
            scope = lifecycleScope,
        )
        session.onControlMessage = ::onControlMessage
        session.onCaptureStarting = { promoteToMediaProjection(true) }
        // Песочница: автосогласие — единственный режим (SessionController.autoAccept = true),
        // запрос от спаренного помощника принимается без диалогов.
        controller = session

        lifecycleScope.launch {
            client.incoming.collect { signal ->
                // Отзыв доступа обрабатываем на уровне сервиса: он трогает хранилище пары,
                // а не сессию. Всё остальное уходит в SessionController.
                when (signal) {
                    is Signal.Revoke -> onRevoke(pair, signal)
                    is Signal.Deeplink -> onDeeplink(client, signal)
                    is Signal.Screencast -> onScreencast(pair, signal)
                    is Signal.PinSetup -> onPinSetup(client)
                    is Signal.UpdateAvailable -> updates.onPush(signal, pair.signalingUrl) { client.send(it) }
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
                    // Отложенный статус фоновой проверки обновлений (UpdateCheckWorker).
                    updates.pendingStatus()?.let(client::send)
                }
            }
        }
        lifecycleScope.launch {
            client.link.collect { link ->
                signalingOnline = link == SignalingClient.Link.ONLINE
                notify(Notifications.status(this@PultService, link == SignalingClient.Link.ONLINE))
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
        // Прогрев adbd-сессии: complete() у диплинка срабатывает только при УЖЕ
        // живой сессии (hasLiveSession), а ленивый коннект в горячий момент поздно.
        // Порт известен после паринга; недоступен — exec вернёт false и ничего не ломает.
        Thread {
            Thread.sleep(4_000) // даём adbd поднять TLS-листенер после включения флага
            val (ok, out) = ru.pult.grandma.control.AdbShell.exec(
                "uiautomator dump /data/local/tmp/ba-ui.xml", 30_000,
            )
            android.util.Log.i("PultAdb", "warmup ok=$ok ${out.take(60)}")
            // Бинари инъекции (LanAgent + scrcpy-server) — раньше пушились руками при
            // настройке; теперь вшиты в assets и ставятся сами при первом живом shell.
            ru.pult.grandma.control.InjectionBinaries.ensure(this)
            // LanAgent — постоянный on-device канал ввода (TCP loopback, не виден
            // BankID, переживает выключение wireless debugging). dex лежит в
            // /data/local/tmp (ставится один раз при настройке, переживает ребуты).
            if (!ru.pult.grandma.control.LanShell.available()) {
                val (spawned, spawnOut) = ru.pult.grandma.control.AdbShell.exec(
                    "setsid sh -c 'CLASSPATH=/data/local/tmp/lanagent.dex " +
                        "app_process / LanAgent 47201 </dev/null >/sdcard/lanagent.log 2>&1 &'",
                    15_000,
                )
                android.util.Log.i("PultLan", "spawn lanagent ok=$spawned ${spawnOut.take(60)}")
            }
        }.start()

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
                foregroundJob?.cancel()
                qrWatcher.stop()
                cancel(Notifications.ID_SESSION)
                restoreRotation()
                // Проекция переживает сессию (на MIUI её из фона не пересоздать), поэтому
                // тип FGS остаётся mediaProjection, пока захват жив: Android 14+ требует
                // именно этот тип, пока проекция активна. Демоутим только когда захвата нет.
                if (controller?.captureReady != true) promoteToMediaProjection(false)
            }

            is SessionController.Ui.Asked ->
                // Автосогласие (песочница): запрос спаренного помощника принимаем сами.
                // Проекция уже поднята → показ без системного диалога (из фона на MIUI его
                // всё равно не открыть, а именно так ломалась вторая сессия). Проекции нет
                // (первый запуск) — системное согласие на захват через ConsentActivity;
                // своих диалогов у приложения нет.
                if (controller?.captureReady == true) {
                    controller?.grant()
                } else {
                    ConsentActivity.requestCapture(this)
                }

            is SessionController.Ui.Session -> {
                // Тип mediaProjection уже включён в onCaptureStarting — до создания проекции.
                // Уведомление с «Стоп» — всё время сессии, без вариантов его убрать.
                notify(Notifications.session(this, state.peerName), Notifications.ID_SESSION)
                qrWatcher.start()
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
            startForeground(Notifications.ID_STATUS, Notifications.status(this, connectedNow))
            return
        }
        val type = if (active) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Пойман на Redmi (HyperOS, Android 15): при исчерпанной дневной квоте dataSync
        // (6 ч) startForeground бросает ForegroundServiceStartNotAllowedException ПРЯМО из
        // onCreate — процесс падает, система перезапускает сервис через ~11 с, квота не
        // восстанавливается → бесконечный краш-луп, и connect() не выполняется никогда
        // (устройство молча офлайн). Поэтому: ловим, переходим на remoteMessaging
        // (независимая квота, тип объявлен в манифесте именно для таких дней), а если
        // и он отказал — остаёмся не-foreground, но ДОЖИВАЕМ до connect(): канал поднимется,
        // поднимет позже BootBridge или будильник-сторож при легальном окне.
        try {
            startForeground(Notifications.ID_STATUS, Notifications.status(this, connectedNow), type)
        } catch (dataSyncBlocked: RuntimeException) {
            android.util.Log.w(TAG, "dataSync FGS blocked ($dataSyncBlocked) — fallback to remoteMessaging")
            ServiceHeartbeat.Deaths.markDeath(this, "fgs-datasync-blocked")
            runCatching {
                startForeground(
                    Notifications.ID_STATUS,
                    Notifications.status(this, connectedNow),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                )
            }.onFailure {
                android.util.Log.w(TAG, "remoteMessaging FGS blocked too ($it) — continue without foreground")
                ServiceHeartbeat.Deaths.markDeath(this, "fgs-all-types-blocked")
            }
        }
    }

    /**
     * Управление от помощника по data-каналу: реальные тап/свайп/навигация — их выполняет
     * служба доступности (RemoteControlService).
     * Координаты приходят в долях кадра (0..1); переводим в пиксели экрана бабушки.
     * Если служба не включена — управление недоступно, но показ работает.
     *
     * ВАЖНО: этот колбэк приходит с signaling-потока libwebrtc, а не с главного.
     * Любое обращение к UI обязано уйти на главный поток — иначе исключение
     * «wrong thread» всплывёт в нативном JNI-колбэке и уронит процесс через SIGABRT.
     */
    private fun onControlMessage(payload: String) {
        android.util.Log.i("PultControl", "recv: $payload")
        val message = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        fun frac(k: String) = message[k]?.jsonPrimitive?.float
        when (message["t"]?.jsonPrimitive?.content) {
            // Реальный тап за бабушку.
            "tap" -> {
                val x = frac("x") ?: return
                val y = frac("y") ?: return
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

            // Дерево доступности для помощника: элементы видны, даже когда видео
            // заблокировано (FLAG_SECURE). Ответ уходит тем же каналом.
            "dump-ui" -> {
                val tree = RemoteControlService.dumpTreeJson()
                android.util.Log.i("PultControl", "dump-ui: tree=${tree?.take(200)}")
                controller?.sendControl("""{"t":"ui-tree","tree":${tree ?: "[]"}}""")
                android.util.Log.i("PultControl", "dump-ui: sent")
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
                // Режим BankID: вход по недавнему диплинку от шлюза — удалённый (автоматика
                // разрешена), иначе местный (BankIdAgent молчит). QR-наблюдение следит
                // за тем же пакетом. Гашение (ниже) от этого не зависит и не меняется.
                if (watcher.lastPackage == ForegroundAppWatcher.BANKID_PACKAGE) BankIdMode.onBankIdForeground()
                qrWatcher.onForegroundPackage(watcher.lastPackage)
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
     * Роняем приложение на рабочий стол в момент старта показа. `MediaProjection`
     * захватывает весь дисплей независимо от того, какое окно наверху, — поэтому помощник
     * начинает видеть настоящий экран бабушки, а не наше окно «Всё работает».
     * Уведомление со «Стоп» остаётся на месте: видимость сохраняется.
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
            signaling?.close()
            signaling = null
            controller = null
            notify(Notifications.status(this, connected = false))
        }, 300)
    }

    /**
     * Поднять BankID с UI: вывести на передний план гарантированно, не сжигая заказ.
     * На MIUI startActivity «успешен» (исключения нет), но BankID остаётся в фоне,
     * а autostart-token при этом уже сгорает — поэтому без проверки переднего плана нельзя.
     *
     * Порядок: прямой startActivity → 4 с опроса UsageStats (com.bankid.bus наверху?) →
     * full-screen intent-уведомление (путь «будильника»; на Android 14+ доступен не всем
     * приложениям) → shell `am start` по УЖЕ живой сессии (на MIUI прямой старт «успешен»
     * молча, BankID остаётся в фоне — shell единственный надёжный путь, см. onDeeplink).
     * Итог — строка raise=direct|fsi|shell|failed:… — с деталями отказа: телефон далеко
     * и логcat недоступен, единственная телеметрия — это err в deeplink-status.
     */
    private fun raiseBankId(url: String): String {
        val watcher = ForegroundAppWatcher(this)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        val direct = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        if (direct.isSuccess) {
            // Без usage-статистики проверить нечем — верим прямому старту (стоковый Android).
            if (!watcher.hasPermission() || awaitBankIdForeground(watcher, DIRECT_VERIFY_MS)) {
                android.util.Log.i(TAG_BANKID, "raise=direct")
                return "direct"
            }
        } else {
            android.util.Log.w(TAG_BANKID, "direct start threw: ${direct.exceptionOrNull()?.message}")
        }
        // Путь «будильника»: FSI-уведомление выводит activity поверх всего даже на MIUI.
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val notifEnabled = nm?.areNotificationsEnabled() == true
        val fsiAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            nm?.canUseFullScreenIntent() == true
        val canFsi = nm != null && notifEnabled && fsiAllowed
        if (canFsi) {
            notify(Notifications.bankIdLaunch(this, url), Notifications.ID_BANKID_LAUNCH)
            // Проверить нечем (нет usage-статистики) — верим пути «будильника»:
            // уведомление гаснет по тапу (autoCancel) или через 2 минуты (timeoutAfter).
            if (!watcher.hasPermission()) {
                android.util.Log.i(TAG_BANKID, "raise=fsi")
                return "fsi"
            }
            val ok = awaitBankIdForeground(watcher, FSI_VERIFY_MS)
            cancel(Notifications.ID_BANKID_LAUNCH)
            if (ok) {
                android.util.Log.i(TAG_BANKID, "raise=fsi")
                return "fsi"
            }
        }
        // Последний резерв — shell (требует живой сессии): на MIUI прямой startActivity
        // «успешен» без исключения, но BankID остаётся в фоне (direct=no-fg) — старое
        // условие direct.isFailure его поэтому никогда не ловило. Раньше тут стояло ещё
        // !canFsi — но на Android 14+ FSI отобран у обычных приложений (fsi=false), и
        // shell при живой сессии надёжнее любого UI-пути. Поднимать сессию из этого
        // места по-прежнему нельзя (паринг/adb_wifi включил бы отладку — BankID встанет),
        // используем ТОЛЬКО уже живую.
        val liveSession = ru.pult.grandma.control.AdbShell.hasLiveSession()
        if (liveSession) {
            // Android 14+ отбирает USE_FULL_SCREEN_INTENT у обычных приложений. Выдаём
            // себе appop через живой shell — одноразово, дальше путь «будильника» живёт
            // сам даже когда сессии нет (fsi станет true).
            ru.pult.grandma.control.AdbShell.exec(
                "cmd appops set $packageName android:full_screen_intent allow", 8_000,
            )
            if (ru.pult.grandma.control.BankIdAgent.openDeeplink(url)) {
                android.util.Log.i(TAG_BANKID, "raise=shell")
                return "shell"
            }
        }
        val detail = "direct=${if (direct.isSuccess) "no-fg" else "throw"};" +
            "notif=$notifEnabled;fsi=$fsiAllowed;" +
            "shell=$liveSession;" +
            "locked=${km?.isDeviceLocked == true};" +
            "screen=${pm?.isInteractive == true};" +
            "usage=${watcher.hasPermission()}"
        android.util.Log.w(TAG_BANKID, "raise=failed $detail")
        return "failed:$detail"
    }

    /** Ждать, пока com.bankid.bus окажется на переднем плане (UsageStats, как в ForegroundAppWatcher). */
    private fun awaitBankIdForeground(watcher: ForegroundAppWatcher, timeoutMs: Long): Boolean {
        if (!watcher.hasPermission()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (watcher.foregroundPackage() == ForegroundAppWatcher.BANKID_PACKAGE) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Диплинк от шлюз-приложения (канал /link): поднимаем BankID на передний план —
     * без WebRTC-сессии. Вход завершаем сами (тачи через scrcpy-инъекцию BankIdAgent)
     * только при уже живой shell-сессии: её подъём включал бы wireless debugging,
     * а BankID с ней работать отказывается. PIN берём из настроек (задаётся один раз
     * на телефоне, никуда не уходит).
     */
    private fun onDeeplink(client: SignalingClient, signal: Signal.Deeplink) {
        // Вся цепочка (включая ожидание PIN через BankIdPinActivity) — с фонового
        // потока: latch.await() на главном даёт взаимную блокировку с UI (ANR).
        Thread { handleDeeplink(client, signal.url) }.start()
    }

    /** Та же цепочка, но без шлюза: локальный диплинк pult://bankid-login (тесты). */
    fun fireTestLogin(url: String) = handleDeeplink(null, url)

    private fun handleDeeplink(client: SignalingClient?, url: String) {
        android.util.Log.i("PultControl", "deeplink received by phone at ${System.currentTimeMillis()}")
        if (!url.startsWith("bankid:///")) {
            client?.send(Signal.DeeplinkStatus(ok = false, stage = "failed", err = "not-a-bankid-url"))
            return
        }
        // Дедупликация: тот же URL в течение 30 с не поднимаем повторно — вторая
        // доставка сжигает autostart-token, и заказ умирает (наблюдалось на MIUI).
        // Важно: дедупим ТОЛЬКО успешный подъём — повтор после отказа должен реально
        // перепробовать пути (иначе Б врёт «opened» на провалившуюся попытку).
        val now = System.currentTimeMillis()
        if (url == lastDeeplinkUrl && now - lastDeeplinkAt < DEEPLINK_DEDUPE_MS && lastDeeplinkOk) {
            android.util.Log.i(TAG_BANKID, "raise=duplicate")
            client?.send(Signal.DeeplinkStatus(ok = true, stage = "opened"))
            return
        }
        lastDeeplinkUrl = url
        lastDeeplinkAt = now
        lastDeeplinkOk = false
        // Вход инициирован шлюзом — режим REMOTE: автоматика BankID разрешена (BankIdMode).
        BankIdMode.onRemoteDeeplink()
        // Горячо обновлённый dex-модуль может перехватить обработку диплинка целиком;
        // тогда встроенный путь (BankIdAgent) не нужен.
        if (ModuleRegistry.active?.onBankIdDeeplink(this, url) == true) {
            client?.send(Signal.DeeplinkStatus(ok = true, stage = "signed"))
            return
        }
        var pin = PinStorage.getBankIdPin(this)
        var lockPin = PinStorage.getLockPin(this)
        if (pin.isEmpty()) {
            // PIN finns inte sparad lokalt — visa BankID-lik skärm och vänta på användaren.
            // Detta händer bara vid första användningen; därefter lagras PIN säkert på denna enhet.
            pin = BankIdPinActivity.requestPin(this) ?: ""
            if (pin.isEmpty()) {
                client?.send(Signal.DeeplinkStatus(ok = false, stage = "failed", err = "pin-cancelled"))
                return
            }
        }
        // Открытие диплинка — сначала напрямую (BAL-исключение SYSTEM_ALERT_WINDOW),
        // затем FSI-уведомление; shell — последний резерв. Весь блок в фоне: проверка
        // переднего плана спит, а shell-путь идёт через TCP-сокет агента — сеть и сон
        // на главном потоке запрещены.
        Thread {
            // Телефон далеко и почти всегда спит запертым (enforceLocked): keyguard
            // блокирует прямой старт BankID, а FSI-путь требует разрешения на уведомления.
            // Будим и отпираем заранее — best-effort: без a11y/PIN сработает только
            // вейклок, но и он нужен (экран погашен = direct start молча мимо).
            ru.pult.grandma.control.ScreenUnlock.unlock(this)
            Thread.sleep(3_000)
            // BankID отказывается работать при включённой беспроводной отладке
            // («Trådlös felsökning») — гасим её ДО подъёма. Неудача записи не блокирует.
            // На MIUI это убивает adbd-листенер и вместе с ним живую shell-сессию,
            // поэтому complete() на этом устройстве не срабатывает — ввод PIN делает
            // ПК через scrcpy-инъекцию по USB (см. tools/scrcpy-inject.js).
            val wifiOff = runCatching {
                android.provider.Settings.Global.putString(contentResolver, "adb_wifi_enabled", "0")
            }.getOrDefault(false)
            android.util.Log.i(TAG_BANKID, "adb_wifi=off ok=$wifiOff")
            // Флаг пишется асинхронно: adbd гаснет ~секунду. Ждём, пока adbd-сессия
            // реально умрёт (LanShell не считаем — LanAgent не зависит от adbd) —
            // иначе BankID при старте видит включённую отладку и показывает экран
            // про bedrägerier (наблюдалось 09-09).
            val offDeadline = System.currentTimeMillis() + 8_000
            while (ru.pult.grandma.control.AdbShell.hasAdbSession()
                && System.currentTimeMillis() < offDeadline) {
                Thread.sleep(300)
            }
            android.util.Log.i(TAG_BANKID, "adb_wifi off confirmed=${!ru.pult.grandma.control.AdbShell.hasAdbSession()}")
            // Мёртвая таска BankID (старая ошибка/протухший заказ) мешает новому:
            // startActivity выводит её наверх со СТАРЫМ экраном. Убиваем заранее.
            ru.pult.grandma.control.AdbShell.exec("am force-stop com.bankid.bus", 8_000)
            Thread.sleep(600)
            val raise = raiseBankId(url)
            if (raise.startsWith("failed")) {
                lastDeeplinkOk = false // повтор той же ссылки должен РЕАЛЬНО перепробовать пути
                client?.send(
                    Signal.DeeplinkStatus(
                        ok = false, stage = "failed",
                        err = raise.removePrefix("failed:").take(120),
                    ),
                )
                return@Thread
            }
            lastDeeplinkOk = true
            client?.send(Signal.DeeplinkStatus(ok = true, stage = "opened"))
            // Автозавершение — только по УЖЕ живой shell-сессии: иначе ensureScrcpy
            // включил бы wireless debugging (паринг/коннект), и BankID встал бы снова.
            // Паринг и включение adb_wifi из этого пути недостижимы.
            if (!ru.pult.grandma.control.AdbShell.hasLiveSession()) {
                android.util.Log.i(TAG_BANKID, "complete skipped: no live shell")
                return@Thread
            }
            android.util.Log.i("PultControl", "deeplink opened, completing BankID locally")
            // Бинари инъекции (scrcpy-server/lanagent) — если вдруг не встали при
            // коннекте, ставим сейчас: сессия жива, горячий момент это лечит.
            ru.pult.grandma.control.InjectionBinaries.ensure(this)
            // Долгая последовательность (десятки секунд) — в этом же фоновом потоке.
            val err = ru.pult.grandma.control.BankIdAgent.complete(pin, lockPin)
            runCatching {
                client?.send(
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
            // Экран далеко и может быть погашен/заперт: чёрный локскрин в эфире бесполезен,
            // поэтому сначала будим и отпираем (вейклок + keyguard-невидимка + свайп + PIN).
            if (signal.on) ru.pult.grandma.control.ScreenUnlock.unlock(this)
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

    /**
     * Сброс BankID-PIN по кнопке из приложения шлюза (канал /link, сигнал pin-setup).
     * PIN мог смениться в самом BankID — тогда автоввод входа старым кодом бесполезен,
     * и человеку у далёкого телефона нужно ввести новый. Порядок: будим/отпираем экран,
     * включаем lowlat-трансляцию (оператор видит ввод), показываем PIN-экран. Сохранение
     * — внутри BankIdPinActivity (PinStorage). Итог уходит в /link как deeplink-status:
     * stage=pin-saved|pin-cancelled.
     */
    private fun onPinSetup(client: SignalingClient) {
        Thread {
            runCatching { ru.pult.grandma.control.ScreenUnlock.unlock(this) }
            Thread.sleep(2_500)
            // Трансляция — тот же путь, что по кнопке «Dela skärm» в B-app.
            runCatching { pairStore.load()?.let { onScreencast(it, Signal.Screencast(on = true)) } }
            // 5 минут: человеку у телефона нужно время подойти и ввести код.
            val pin = ru.pult.grandma.ui.BankIdPinActivity.requestPin(this, 300_000)
            android.util.Log.i("PultControl", "pin-setup result=${if (pin != null) "saved" else "cancelled/timeout"}")
            runCatching {
                client.send(
                    Signal.DeeplinkStatus(
                        ok = !pin.isNullOrEmpty(),
                        stage = if (pin != null) "pin-saved" else "pin-cancelled",
                    ),
                )
            }
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
        // Будим сторожа через секунду: точный будильник (с резервом-неточным) пробивает
        // и Doze, и MIUI-отложки — тупой set() на 12+ может уехать на минуту.
        scheduleRestart(1_000L, 2)
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Будильник «через delayMs разбуди сторожа». Идём через broadcast-сторожа, а не
     * напрямую в сервис: на срабатывании сторож сам сверит, жив сервис или мёртв
     * (FGS-старт мёртвым из фона на 12+ отклоняется — PultService.start это проглатывает,
     * но зачем провоцировать отказ, если можно проверить заранее).
     */
    private fun scheduleRestart(delayMs: Long, requestCode: Int) {
        val alarm = getSystemService(android.app.AlarmManager::class.java) ?: return
        val wake = PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, ServiceHeartbeat::class.java).setAction(ServiceHeartbeat.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = android.os.SystemClock.elapsedRealtime() + delayMs
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                at,
                wake,
            )
        }.onFailure {
            runCatching {
                alarm.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, wake)
            }
        }
    }

    override fun onDestroy() {
        ServiceHeartbeat.Deaths.markDeath(this, "onDestroy")
        // Сюда попадаем только при остановке системой (сами себя не гасим): планируем
        // подъём, чтобы не ждать планового окна сторожа. При kill -9/onDestroy не вызовется —
        // там работают crash-будильник и плановые сторожа.
        scheduleRestart(1_000L, 3)
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        signalingOnline = false
        // Проекция живёт между сессиями, но не должна пережить сам сервис.
        controller?.releaseCapture()
        qrWatcher.stop()
        tunnel?.stop()
        tunnel = null
        signaling?.close()
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

    /** Подпись устройства для панели оператора: своя из настроек или «производитель модель» (≤40). */
    private fun deviceLabel(): String {
        val custom = PultApp.prefs(this).getString("device_label", null)?.trim().orEmpty()
        val base = custom.ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" }
        return base.take(40)
    }

    /** Локальный IP-адрес устройства для отображения в B-app (Wi-Fi/LTE). */
    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }
                ?.hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "PultService"

        /** Подъём BankID по диплинку: прямой старт, проверка переднего плана, FSI, shell. */
        private const val TAG_BANKID = "PultBankId"
        private const val DIRECT_VERIFY_MS = 4_000L
        private const val FSI_VERIFY_MS = 6_000L
        private const val DEEPLINK_DEDUPE_MS = 30_000L

        const val ACTION_GRANT = "ru.pult.grandma.GRANT"
        const val ACTION_DENY = "ru.pult.grandma.DENY"
        const val ACTION_STOP_SESSION = "ru.pult.grandma.STOP_SESSION"
        const val ACTION_PAIR_CHANGED = "ru.pult.grandma.PAIR_CHANGED"
        const val ACTION_PAIR_ADB = "ru.pult.grandma.PAIR_ADB"
        const val ACTION_TEST_LOGIN = "ru.pult.grandma.TEST_LOGIN"
        /** Удалённая реанимация: жёсткий переподключение сигналинга (Reanimate.restart). */
        const val ACTION_REANIMATE_RESTART = "ru.pult.grandma.REANIMATE_RESTART"
        const val EXTRA_URL = "test_url"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Свежесть связи с сигналингом для тихого экрана статуса (MainActivity читает). */
        @Volatile
        var signalingOnline: Boolean = false
            private set

        /** Жив ли сервис прямо сейчас. Плановые сторожа и a11y-вахта сверяются этим. */
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            return am.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == PultService::class.java.name }
        }

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

        /** Старт сервиса с явным action (реанимация и прочие команды). */
        fun startWithAction(context: Context, action: String) {
            try {
                context.startForegroundService(
                    Intent(context, PultService::class.java).setAction(action),
                )
            } catch (notAllowed: ForegroundServiceStartNotAllowedException) {
                android.util.Log.w(TAG, "старт FGS с action=$action запрещён системой")
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

        /** Локальный вход BankID по нашему диплинку (pult://bankid-login?url=...). */
        fun fireTestLogin(context: Context, url: String) {
            context.startService(
                Intent(context, PultService::class.java)
                    .setAction(ACTION_TEST_LOGIN)
                    .putExtra(EXTRA_URL, url),
            )
        }
    }
}
