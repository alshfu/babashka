package ru.pult.grandma.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Выживание сервиса на агрессивных прошивках (MIUI/HyperOS, EMUI/HarmonyOS, ColorOS, FuntouchOS).
 *
 * 99% телефонов пожилых людей — именно такие. Без этих исключений сервис умирает через
 * 10–30 минут после блокировки экрана, и запрос помощи до бабушки не доходит. Часть
 * экранов (автозапуск, «нет ограничений батареи») нельзя выставить программно — только
 * привести туда пользователя (это делает семья при настройке, не бабушка).
 *
 * Каждый бренд прячет автозапуск в своём Activity. Список ниже — известные пути; открываем
 * с fallback на общий экран настроек приложения, если конкретный Activity отсутствует.
 */
object BrandSurvival {

    enum class Brand { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, OTHER }

    val brand: Brand by lazy {
        when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> Brand.XIAOMI
            "huawei", "honor" -> Brand.HUAWEI
            "oppo", "realme", "oneplus" -> Brand.OPPO
            "vivo" -> Brand.VIVO
            "samsung" -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }

    val brandName: String
        get() = when (brand) {
            Brand.XIAOMI -> "Xiaomi / Redmi"
            Brand.HUAWEI -> "Huawei / Honor"
            Brand.OPPO -> "Oppo / Realme"
            Brand.VIVO -> "Vivo"
            Brand.SAMSUNG -> "Samsung"
            Brand.OTHER -> Build.MANUFACTURER
        }

    // ── Оптимизация батареи (стандартный Android) ────────────────────────────

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Диалог «разрешить работу без ограничений батареи» — стандартный системный. */
    fun requestIgnoreBatteryOptimization(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Экраны управления батареей: сначала бренд-специфичный (у MIUI/ColorOS есть свой,
     * который и решает per-app ограничение), затем стандартный системный диалог.
     * Проверенные пути из практики.
     */
    fun batteryIntents(context: Context): List<Intent> {
        val oem = when (brand) {
            Brand.XIAOMI -> listOf(
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity").apply {
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "Pult")
                },
            )
            Brand.OPPO -> listOf(
                component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            )
            else -> emptyList()
        }
        return oem + requestIgnoreBatteryOptimization(context)
    }

    // ── Автозапуск (у каждого бренда своё) ───────────────────────────────────

    /**
     * Экран автозапуска/«запуск в фоне». Возвращает список кандидатов: пробуем по очереди,
     * пока какой-то не откроется (Activity различаются между версиями прошивок).
     * Последний в списке — общий экран сведений о приложении, он есть всегда.
     */
    fun autostartIntents(context: Context): List<Intent> {
        val candidates = when (brand) {
            Brand.XIAOMI -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            Brand.HUAWEI -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            Brand.OPPO -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                // Новые ColorOS переехали на пакет com.oplus.* — обязательный fallback.
                component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Brand.VIVO -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            Brand.SAMSUNG -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            Brand.OTHER -> emptyList()
        }
        return candidates + appDetailsIntent(context)
    }

    /** Нужен ли вообще шаг «автозапуск»: на «чистом» Android — нет. */
    val needsAutostartStep: Boolean get() = brand != Brand.OTHER && brand != Brand.SAMSUNG

    /** Инструкция для конкретного бренда — что нажать на открывшемся экране. */
    fun autostartHint(): String = when (brand) {
        Brand.XIAOMI -> "Hitta Pult och aktivera ”Autostart”. Gå sedan tillbaka."
        Brand.HUAWEI -> "Hitta Pult, stäng av ”Hantera automatiskt” och aktivera " +
            "”Autostart”, ”Sekundär start” och ”Kör i bakgrunden”. Gå tillbaka."
        Brand.OPPO -> "Hitta Pult och tillåt ”Autostart”. Gå tillbaka."
        Brand.VIVO -> "Hitta Pult och tillåt start i bakgrunden. Gå tillbaka."
        else -> "Tillåt appen att köra i bakgrunden och gå tillbaka."
    }

    /** «Закрепить в недавних» — MIUI/прочие убивают незакреплённые. Инструкция, не Intent. */
    fun lockInRecentsHint(): String = when (brand) {
        Brand.XIAOMI -> "Öppna senaste appar, dra Pult-kortet nedåt och tryck på låset — " +
            "då stänger inte systemet appen."
        else -> "I listan över senaste appar låser du fast Pult (låsikonen) " +
            "så att systemet inte avlastar den."
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Первый Intent из списка, который реально можно открыть на этом устройстве. */
    fun firstResolvable(context: Context, intents: List<Intent>): Intent? =
        intents.firstOrNull { it.resolveActivity(context.packageManager) != null }
}
