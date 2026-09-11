package ru.pult.grandma.service

import android.content.Context
import android.util.Log

/**
 * Удалённая реанимация телефона по команде с сервера (FCM data-push «reanimate»).
 *
 * Три уровня, от мягкого к жёсткому (жёсткость решает оператор на B-app):
 *  wake    — сервис уже поднят вызывающей стороной (PultMessagingService), тут no-op;
 *  restart — процесс жив (FCM дошёл), но сигналинг мог застрять: жёсткий
 *            переподключение сокета через PultService.reconnect();
 *  reboot  — полный перезапуск устройства через shell (svc power reboot).
 *            Сначала пробуем shell как есть (LanAgent живёт на loopback и не
 *            требует wireless debugging), не вышло — включаем adb_wifi
 *            (WRITE_SECURE_SETTINGS) и ждём подъёма adbd до 60 с.
 *
 * Работает, пока жив системный стек (FCM-радио доставило пуш). Полностью
 * подвисшее железо (мертвый радиомодуль, kernel panic) software не спасти —
 * тогда только физическая кнопка питания.
 *
 * Банковский контур НЕ трогаем: это путь вне BankIdAgent, на вход в BankID
 * влияет лишь постфактум (после ребута всё само поднимается: BootReceiver →
 * PultService → adb_wifi включится заново, см. PultService.onCreate).
 */
object Reanimate {

    private const val TAG = "PultReanimate"

    /** Мягкий рестарт: процесс жив, рвём сигналинг-сокет — сервис сам переподключится. */
    fun restart(context: Context) {
        Log.i(TAG, "restart requested")
        PultService.startWithAction(context, PultService.ACTION_REANIMATE_RESTART)
    }

    /** Жёсткий: перезагрузка устройства через shell (UID 2000). */
    fun reboot(context: Context) {
        Log.i(TAG, "reboot requested")
        PultService.start(context) // сервис нужен живым на случай отказа ребута
        Thread {
            val ok = waitForShell()
            Log.i(TAG, "shell ready=$ok")
            if (ok) {
                val (sent, out) = ru.pult.grandma.control.AdbShell.exec("svc power reboot", 10_000)
                Log.i(TAG, "svc power reboot: sent=$sent $out")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Дождаться живого shell: сначала как есть (LanAgent), затем с включением
     * wireless debugging и опросом adbd. Дедлайн 60 с — дальше телефон слишком
     * болен для software-реанимации.
     */
    private fun waitForShell(): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val (ok, _) = ru.pult.grandma.control.AdbShell.exec("echo reanimate-ok", 8_000)
            if (ok) return true
            runCatching {
                // Контекст приложения: глобальная настройка, WRITE_SECURE_SETTINGS
                // выдан при настройке устройства.
                ru.pult.grandma.control.AdbShell.enableWirelessDebugging()
            }
            Thread.sleep(3_000)
        }
        return false
    }
}
