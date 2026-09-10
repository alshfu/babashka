package ru.pult.grandma.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.pult.grandma.PultApp
import ru.pult.grandma.R
import ru.pult.grandma.ui.MainActivity

/**
 * Уведомления — главный признак видимости приложения.
 *
 * Постоянное уведомление висит всегда (спокойный значок «на связи» у батарейки),
 * а во время сеанса рядом с батарейкой появляется отдельный значок-«глаз»: внук видит
 * экран. Ни то, ни другое не отключается из приложения — это условие легальности,
 * а не пользовательская настройка (docs/security.md §2).
 */
object Notifications {

    const val ID_STATUS = 1
    const val ID_SESSION = 2
    const val ID_BANKID_LAUNCH = 3

    /** Постоянное: имя службы + одно слово состояния. Тихо, без тревоги и кнопок. */
    fun status(context: Context, connected: Boolean): Notification =
        NotificationCompat.Builder(context, PultApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ready)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(if (connected) R.string.notif_active else R.string.notif_no_network))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * Идёт сеанс. Отдельный значок-«глаз» в статусбаре — символ, что внук управляет
     * устройством. Кнопка «Стоп» — прямо в уведомлении.
     */
    fun session(context: Context, peerName: String): Notification =
        NotificationCompat.Builder(context, PultApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_viewing)
            .setContentTitle(context.getString(R.string.notif_session_title, peerName))
            .setContentText(context.getString(R.string.notif_session_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_stat_viewing,
                context.getString(R.string.notif_stop),
                stopSession(context),
            )
            .build()

    /**
     * Подъём BankID поверх всего: full-screen intent (путь «будильника») — на MIUI
     * единственный способ вывести activity на передний план из фона без shell.
     * Гасится, как только BankID обнаружен на переднем плане, либо по таймауту.
     */
    fun bankIdLaunch(context: Context, url: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQ_BANKID_LAUNCH,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, PultApp.CHANNEL_BANKID_LAUNCH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("BankID")
            .setContentText(context.getString(R.string.notif_bankid_open))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
    }

    private const val REQ_BANKID_LAUNCH = 3

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopSession(context: Context) = PendingIntent.getService(
        context,
        1,
        Intent(context, PultService::class.java).setAction(PultService.ACTION_STOP_SESSION),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
