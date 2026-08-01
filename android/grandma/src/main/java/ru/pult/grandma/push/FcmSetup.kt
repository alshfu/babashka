package ru.pult.grandma.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import ru.pult.grandma.BuildConfig

/**
 * Ручная инициализация Firebase без google-services.json.
 *
 * Так проект собирается и работает без креденшелов на этапе разработки, а FCM
 * включается, как только в сборку переданы параметры Firebase (FCM_* в BuildConfig).
 * Это осознанный компромисс: обычно подключают плагин google-services, но он требует
 * google-services.json на этапе сборки, чего у нас пока нет.
 */
object FcmSetup {

    private const val TAG = "PultFcm"

    val isConfigured: Boolean
        get() = BuildConfig.FCM_APP_ID.isNotEmpty() &&
            BuildConfig.FCM_API_KEY.isNotEmpty() &&
            BuildConfig.FCM_PROJECT_ID.isNotEmpty() &&
            BuildConfig.FCM_SENDER_ID.isNotEmpty()

    /** Вызывается из PultApp.onCreate. Без параметров — тихо ничего не делает. */
    fun init(context: Context) {
        if (!isConfigured) {
            Log.i(TAG, "FCM не сконфигурирован — работаем без удалённого пробуждения")
            return
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) return

        runCatching {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build(),
            )
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                PultMessagingService.saveToken(context, token)
                Log.i(TAG, "FCM токен получен")
            }
        }.onFailure { Log.w(TAG, "инициализация FCM не удалась: ${it.message}") }
    }
}
