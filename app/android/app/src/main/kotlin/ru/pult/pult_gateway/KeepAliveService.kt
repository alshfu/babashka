package ru.pult.pult_gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Удерживает процесс живым, пока приложение в фоне: без этого Samsung (Freecess)
 * замораживает приложение через ~8 с после ухода в фон и рвётся WebSocket-канал
 * к серверу — а диплинк от Swedbank приходит как раз когда мы в фоне.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Канал шлюза", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pult Gateway активен")
            .setContentText("Канал к телефону в Швеции поддерживается")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_STICKY
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL_ID = "gateway_link"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
