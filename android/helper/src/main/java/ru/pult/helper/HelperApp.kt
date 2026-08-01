package ru.pult.helper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Приложение помощника.
 *
 * Уведомления здесь другого рода, чем у бабушки: они сообщают о её доступности и об
 * исходе запроса, а не о слежке. «Бабушка не выходит на связь больше 6 часов» — тоже
 * уведомление помощнику: молчаливо умерший сервис на её телефоне должен быть заметен.
 */
class HelperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_PRESENCE,
                "Связь с бабушкой",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_PRESENCE = "pult_presence"
    }
}
