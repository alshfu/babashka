package ru.pult.grandma.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ru.pult.grandma.PultApp
import ru.pult.grandma.service.PultService
import ru.pult.grandma.service.Reanimate
import ru.pult.grandma.service.ServiceHeartbeat

/**
 * FCM — ГЛАВНЫЙ механизм подъёма сервиса после того, как прошивка его убила
 * (глубокий разряд, долгий сон). Внук нажимает «Помочь» → сервер шлёт high-priority
 * data-push → система будит процесс и вызывает этот сервис → мы поднимаем PultService,
 * дальше поднимается WebRTC.
 *
 * Именно high-priority data-сообщение (а не notification) позволяет запустить
 * foreground-сервис из фонового пробуждения.
 */
class PultMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Токен нужен серверу, чтобы будить именно этот телефон. Сохраняем и, если связь
        // есть, сервис отправит его на сигналинг (register-push).
        saveToken(this, token)
        PultService.start(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Разбудили: поднимаем сервис немедленно. Тип сообщения не важен — сам факт push
        // означает «внук зовёт», а запрос помощи придёт уже по сигналингу.
        ServiceHeartbeat.Deaths.markAlive(this, "fcm-wake")
        PultService.start(this)
        // Команды реанимации (server → POST /api/reanimate → FCM data-push):
        // restart — мягкий перезапуск сигналинга; reboot — перезагрузка устройства.
        // Любой другой/пустой action — просто wake (сервис уже поднят выше).
        if (message.data["t"] == "reanimate") {
            when (message.data["action"]) {
                "restart" -> Reanimate.restart(this)
                "reboot" -> Reanimate.reboot(this)
            }
        }
    }

    companion object {
        private const val KEY_TOKEN = "fcm_token"

        fun saveToken(context: Context, token: String) {
            PultApp.prefs(context).edit().putString(KEY_TOKEN, token).apply()
        }

        fun token(context: Context): String? =
            PultApp.prefs(context).getString(KEY_TOKEN, null)
    }
}
