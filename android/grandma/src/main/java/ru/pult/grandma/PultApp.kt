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
        // Наш ADB-ключ (wireless debugging) — должен существовать до первого паринга.
        ru.pult.grandma.control.AdbShell.init(this)
        // Alpha 0.4: единое приложение. Фоновый сервис устройства поднимаем ТОЛЬКО если этот
        // телефон выбран как «Устройство». Роль «Панель» и не выбранная роль — ничего не
        // стартуют, дальше решает экран выбора роли (RoleActivity).
        if (getRole(this) == ROLE_DEVICE) startDevice(this)
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

        // Роль устройства в едином приложении (Alpha 0.4).
        const val ROLE_DEVICE = "device"
        const val ROLE_PANEL = "panel"
        private const val KEY_ROLE = "app_role"

        fun prefs(context: Context) =
            context.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)

        fun getRole(context: Context): String? = prefs(context).getString(KEY_ROLE, null)
        fun setRole(context: Context, role: String) =
            prefs(context).edit().putString(KEY_ROLE, role).apply()

        /** Поднять телефон как «Устройство»: демо-пара + фоновый сервис + FCM. */
        fun startDevice(context: Context) {
            ru.pult.grandma.setup.DemoBootstrap.ensurePaired(
                context,
                ru.pult.core.pairing.EncryptedPairStore(context),
            )
            ru.pult.grandma.push.FcmSetup.init(context)
            PultService.start(context)
        }
    }
}
