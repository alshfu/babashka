package ru.pult.grandma

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import ru.pult.grandma.service.PultService

/**
 * Приложение бабушки.
 *
 * Она его не открывает — оно живёт в фоне. Но живёт видимо: каналы уведомлений
 * создаются здесь и оба неотключаемы из интерфейса приложения (docs/security.md §2).
 */
class PultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Демо-сборка: подставляем фиксированную пару и turnkey, чтобы APK работал сразу.
        ru.pult.grandma.setup.DemoBootstrap.ensurePaired(
            this,
            ru.pult.core.pairing.EncryptedPairStore(this),
        )
        // FCM — удалённое пробуждение убитого сервиса (главный механизм после разряда/сна).
        ru.pult.grandma.push.FcmSetup.init(this)
        // Сервис поднимается сам: бабушке не нужно ничего запускать руками.
        // Подъём из Application класса — ещё одна страховка при старте процесса.
        PultService.start(this)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Постоянное уведомление. Тихое, но всегда на виду.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.notif_status_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_status_text)
                setShowBadge(false)
            },
        )

        // Идёт показ экрана — это должно быть заметно, поэтому HIGH.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                "Идёт показ экрана",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Видно всё время, пока помощник смотрит ваш экран"
                setShowBadge(true)
            },
        )
    }

    companion object {
        const val CHANNEL_STATUS = "pult_status"
        const val CHANNEL_SESSION = "pult_session"

        fun prefs(context: Context) =
            context.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)
    }
}
