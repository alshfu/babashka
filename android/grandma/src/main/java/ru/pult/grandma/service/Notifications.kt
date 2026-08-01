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

    /** Постоянное «Петя на связи». Значок в статусбаре спокойный, без тревоги. */
    fun status(context: Context, peerName: String?, connected: Boolean): Notification {
        val title = when {
            !connected -> context.getString(R.string.notif_offline)
            peerName != null -> context.getString(R.string.notif_status_connected, peerName)
            else -> context.getString(R.string.notif_status_title)
        }
        return NotificationCompat.Builder(context, PultApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ready)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_status_text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

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
