package ru.pult.grandma.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск после перезагрузки и обновления приложения.
 *
 * Ловим все варианты загрузки: обычную, «до разблокировки» (LOCKED_BOOT_COMPLETED),
 * и вендорные «быстрые» интенты Xiaomi/Huawei/HTC. Но честно: после глубокого разряда
 * китайские прошивки часто не доставляют даже эти интенты — главный механизм подъёма
 * убитого сервиса это FCM high-priority push, а автозапуск лишь страховка.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Любой из известных интентов загрузки/обновления — поднимаем сервис и сторожей.
        ServiceHeartbeat.Deaths.markAlive(context, "boot:${intent.action}")
        ServiceHeartbeat.schedule(context)
        BootDiagnostics.run(context)
        // Через мост: прямой старт PultService при загрузке запрещён (mediaProjection в
        // типах), а dataSync-мост легален — он поднимет основной сервис из foreground.
        runCatching {
            context.startForegroundService(Intent(context, BootBridgeService::class.java))
        }
    }
}
