package ru.pult.grandma.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Split-tunnel VPN бабушки — КАРКАС (стадия 2).
 *
 * Ключевое в устройстве, а не в словах:
 *  - `addAllowedApplication(pkg)` вызывается ТОЛЬКО для приложений из белого списка семьи.
 *    Это значит: в туннель попадают лишь они, а ВСЁ остальное (включая любые банки) идёт
 *    напрямую, мимо VPN. «Завернуть весь трафик» технически невозможно — мы не вызываем
 *    установку без allow-списка.
 *  - Банки в allow-список не попадают (TunnelPolicy их отсекает), значит их пакеты вообще
 *    не входят в TUN.
 *  - Пока движок (Xray) не подключён — туннель НЕ поднимаем, чтобы не отрезать выбранные
 *    приложения от сети. Каркас готов и ждёт стадию 3.
 *
 * Прозрачность: VpnService рисует системный значок «ключ» в статус-баре — бабушка всегда
 * видит, что туннель активен. Это как постоянное уведомление сервиса: скрытого режима нет.
 */
class PultVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    // Реальный движок Xray (стадия 3). Пока нативный AAR не добавлен — isReady()=false,
    // и туннель не поднимается (сеть выбранных приложений не рвётся). Слот готов.
    private val engine: TunnelEngine by lazy { XrayEngine(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        startForeground(NOTIF_ID, notification())

        val policy = TunnelPolicy(this)
        val allowed = policy.allowedApps()
        if (allowed.isEmpty()) {
            // Нечего туннелировать — не поднимаем VPN (иначе поведение зависело бы от роутов).
            Log.i(TAG, "allow-список пуст: туннель не нужен")
            return
        }

        val config = TunnelConfig.load(this)
        if (!engine.isReady() || config.isEmpty()) {
            // Стадия 2: движка/конфига нет. НЕ поднимаем мёртвый туннель — иначе выбранные
            // приложения потеряют сеть. Каркас готов, ждём Xray (стадия 3).
            Log.i(TAG, "каркас готов; движок Xray не подключён — туннель не поднимаем (стадия 3)")
            return
        }

        val builder = Builder()
            .setSession("Pult")
            .setMtu(1500)
            // Приватная сеть внутри туннеля; наружу пакеты уносит движок.
            .addAddress("10.111.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)

        // СЕРДЦЕ split-tunnel: в туннель — только разрешённые приложения. Банки исключены
        // политикой и сюда не попадут. Всё, что не добавлено, идёт напрямую.
        for (pkg in allowed) {
            runCatching { builder.addAllowedApplication(pkg) }
                .onFailure { Log.w(TAG, "нет приложения $pkg — пропускаем") }
        }
        // Себя в туннель не заворачиваем: сигналинг/захват должны идти своим путём.
        runCatching { builder.addDisallowedApplication(packageName) }

        val iface = runCatching { builder.establish() }.getOrNull()
        if (iface == null) {
            Log.w(TAG, "establish() не удался")
            return
        }
        tun = iface
        engine.start(iface, config) { protect(it) }
        Log.i(TAG, "туннель поднят для ${allowed.size} приложений; банки — мимо")
    }

    private fun teardown() {
        runCatching { engine.stop() }
        runCatching { tun?.close() }
        tun = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Пользователь отключил VPN системным способом — уважаем и гасим.
        teardown()
        stopSelf()
        super.onRevoke()
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Доступ к сервисам", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Pult — доступ к приложениям включён")
            .setContentText("Через защищённый канал идут только выбранные приложения")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PultVpn"
        private const val CHANNEL = "pult_vpn"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "ru.pult.grandma.vpn.STOP"
    }
}
