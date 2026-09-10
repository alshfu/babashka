package ru.pult.grandma.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Мост автозапуска после перезагрузки.
 *
 * Android 14+ запрещает старт FGS из фона для типа mediaProjection — а у PultService он
 * объявлен в манифесте, поэтому BOOT_COMPLETED → PultService.start() отклоняется системой.
 * remoteMessaging при загрузке разрешён (dataSync с Android 15 — уже нет): поднимаем этот
 * минимальный сервис, из foreground-состояния стартуем основной (теперь легально) и сразу
 * уходим. Цепочка автономности без компьютера:
 * ребут → BootBridge → PultService → каналы сами.
 */
class BootBridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notifications.ID_STATUS,
            Notifications.status(this, connected = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        PultService.start(this)
        stopSelf()
        return START_NOT_STICKY
    }
}
