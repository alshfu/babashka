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
        // Ставим ПЕРВЫМ, до всего, что может бросить: непойманное исключение убивает
        // процесс вместе со сторожами, и сервис лежит до следующего планового окна
        // (5–15 мин простоя). Будильник будит ServiceHeartbeat — он поднимет сервис,
        // если тот мёртв. Дефолтный обработчик вызываем в конце: процесс падает как
        // раньше (видно в crash-логах), просто больше не остаётся лежать.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                ru.pult.grandma.service.ServiceHeartbeat.Deaths
                    .markDeath(this, "crash:${throwable.javaClass.simpleName}")
                ru.pult.grandma.service.ServiceHeartbeat.armCrashRestart(this)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        createChannels()
        // Наш ADB-ключ (wireless debugging) — должен существовать до первого паринга.
        ru.pult.grandma.control.AdbShell.init(this)
        // Песочница: единственная роль — «Устройство». Фоновый сервис поднимаем всегда.
        startDevice(this)
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
                getString(R.string.notif_channel_session_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notif_channel_session_desc)
                setShowBadge(true)
            },
        )

        // Подъём BankID по диплинку поверх других окон (full-screen intent). HIGH
        // обязателен: иначе система не покажет full-screen activity.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BANKID_LAUNCH,
                getString(R.string.notif_channel_bankid_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notif_channel_bankid_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_STATUS = "pult_status"
        const val CHANNEL_SESSION = "pult_session"
        const val CHANNEL_BANKID_LAUNCH = "bankid_launch"

        // Роль устройства. В песочнице единственная — «Устройство»; преф сохраняем
        // для совместимости со старыми установками (по умолчанию — device, без выбора).
        const val ROLE_DEVICE = "device"
        private const val KEY_ROLE = "app_role"

        fun prefs(context: Context) =
            context.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)

        fun getRole(context: Context): String = prefs(context).getString(KEY_ROLE, null) ?: ROLE_DEVICE
        fun setRole(context: Context, role: String) =
            prefs(context).edit().putString(KEY_ROLE, role).apply()

        /** Поднять телефон как «Устройство»: фоновый сервис + FCM. */
        fun startDevice(context: Context) {
            ru.pult.grandma.push.FcmSetup.init(context)
            PultService.start(context)
        }
    }
}
