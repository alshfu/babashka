package ru.pult.grandma.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Один шаг мастера настройки. Мастер проходит семья при настройке телефона бабушки —
 * по одному разрешению за раз, с проверкой после возврата из системных настроек.
 *
 * `granted` — проверяемо ли выполнение (для автозапуска проверить нельзя, там `null`:
 * такой шаг подтверждает человек).
 */
sealed class SetupStep(
    val id: String,
    val title: String,
    val explain: String,
    val hint: String,
) {
    /** Открыть нужный системный экран. Первый разрешимый Intent из списка. */
    abstract fun intent(context: Context): Intent?

    /** Выполнено ли. null — проверить нельзя (подтверждает человек кнопкой «Готово»). */
    open fun granted(context: Context): Boolean? = null

    object Battery : SetupStep(
        id = "battery",
        title = "Работа без ограничений батареи",
        explain = "Чтобы помощь была доступна в любой момент, телефон не должен «усыплять» приложение.",
        hint = "Нажмите «Разрешить» в появившемся окне.",
    ) {
        override fun intent(context: Context) =
            BrandSurvival.firstResolvable(context, BrandSurvival.batteryIntents(context))
        override fun granted(context: Context) = BrandSurvival.isBatteryOptimizationIgnored(context)
    }

    object Overlay : SetupStep(
        id = "overlay",
        title = "Показ рамки поверх экрана",
        explain = "Во время сеанса на экране бабушки видна зелёная рамка и кнопка «Стоп».",
        hint = "Включите «Поверх других приложений» для «Пульта» и вернитесь назад.",
    ) {
        override fun intent(context: Context) = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        )
        override fun granted(context: Context) = Settings.canDrawOverlays(context)
    }

    object Notifications : SetupStep(
        id = "notifications",
        title = "Постоянное уведомление",
        explain = "Уведомление «на связи» показывает, что приложение работает. Скрыть его нельзя.",
        hint = "Разрешите уведомления для «Пульта» и вернитесь назад.",
    ) {
        override fun intent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        override fun granted(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    object UsageAccess : SetupStep(
        id = "usage",
        title = "Защита банковских приложений",
        explain = "Когда бабушка откроет банк, показ автоматически гаснет для помощника.",
        hint = "Включите доступ к статистике для «Пульта» и вернитесь назад.",
    ) {
        override fun intent(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        override fun granted(context: Context): Boolean {
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        }
    }

    /** Автозапуск: проверить нельзя, подтверждает человек. Показываем бренд-инструкцию. */
    object Autostart : SetupStep(
        id = "autostart",
        title = "Автозапуск (${BrandSurvival.brandName})",
        explain = "Без автозапуска ${BrandSurvival.brandName} закрывает приложение через 10–30 минут " +
            "и помощь становится недоступна.",
        hint = BrandSurvival.autostartHint(),
    ) {
        override fun intent(context: Context) =
            BrandSurvival.firstResolvable(context, BrandSurvival.autostartIntents(context))
    }

    /** Закрепление в недавних: только инструкция, системного экрана нет. */
    object LockRecents : SetupStep(
        id = "lock-recents",
        title = "Закрепить в недавних",
        explain = "Чтобы система не выгружала приложение из памяти.",
        hint = BrandSurvival.lockInRecentsHint(),
    ) {
        override fun intent(context: Context): Intent? = null
    }

    companion object {
        /** Порядок шагов зависит от бренда: автозапуск и закрепление — только где нужно. */
        fun all(): List<SetupStep> = buildList {
            add(Battery)
            add(Overlay)
            add(Notifications)
            add(UsageAccess)
            if (BrandSurvival.needsAutostartStep) add(Autostart)
            if (BrandSurvival.brand != BrandSurvival.Brand.OTHER) add(LockRecents)
        }
    }
}
