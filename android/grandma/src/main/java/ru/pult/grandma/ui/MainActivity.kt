package ru.pult.grandma.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.grandma.R
import ru.pult.grandma.setup.BrandSurvival

/**
 * Главный экран бабушки — только статус, без единого действия.
 *
 * Настройка и отзыв помощника здесь отсутствуют намеренно. Но если телефон ещё не
 * настроен под выживание сервиса (не выданы критичные разрешения) — открываем мастер:
 * это первый запуск, его проходит семья, а не бабушка.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Alpha 0.4 all-in-one: маршрутизация по роли. Роль не выбрана → экран выбора;
        // «Панель» → встроенная веб-панель; «Устройство» → обычный экран статуса ниже.
        when (ru.pult.grandma.PultApp.getRole(this)) {
            null -> { startActivity(Intent(this, RoleActivity::class.java)); finish(); return }
            ru.pult.grandma.PultApp.ROLE_PANEL -> { startActivity(Intent(this, PanelActivity::class.java)); finish(); return }
        }

        setContentView(R.layout.activity_main)
        // Сценарии — функция V2 (управление). В V1 (без accessibility) кнопки нет:
        // ScenariosActivity в v1-манифесте не объявлена, запускать нечего.
        val scenarios = findViewById<android.widget.Button>(R.id.scenarios)
        if (ru.pult.grandma.BuildConfig.CONTROL_ENABLED) {
            scenarios.setOnClickListener { startActivity(Intent(this, ScenariosActivity::class.java)) }
        } else {
            scenarios.visibility = android.view.View.GONE
        }
        val vpn = findViewById<android.widget.Button>(R.id.vpn)
        if (ru.pult.grandma.BuildConfig.CONTROL_ENABLED) {
            vpn.setOnClickListener { startActivity(Intent(this, VpnActivity::class.java)) }
        } else {
            vpn.visibility = android.view.View.GONE
        }
        // Эмулятор BankID — только в отладке, для записи сценариев владельцем.
        val bankidEmulator = findViewById<android.widget.Button>(R.id.bankidEmulator)
        if (ru.pult.grandma.BuildConfig.DEBUG) {
            bankidEmulator.setOnClickListener { startActivity(Intent(this, BankIdEmulatorActivity::class.java)) }
        } else {
            bankidEmulator.visibility = android.view.View.GONE
        }
        // PIN для локального завершения BankID по диплинку — только V2 (шлюз).
        // Хранится в настройках устройства, никуда не передаётся.
        val pinInput = findViewById<android.widget.EditText>(R.id.bankidPin)
        val pinSave = findViewById<android.widget.Button>(R.id.bankidPinSave)
        val lockPinInput = findViewById<android.widget.EditText>(R.id.lockPin)
        if (ru.pult.grandma.BuildConfig.CONTROL_ENABLED) {
            val prefs = ru.pult.grandma.PultApp.prefs(this)
            pinInput.setText(prefs.getString(ru.pult.grandma.service.PultService.PREF_BANKID_PIN, ""))
            lockPinInput.setText(prefs.getString(ru.pult.grandma.service.PultService.PREF_LOCK_PIN, ""))
            pinSave.setOnClickListener {
                prefs.edit()
                    .putString(ru.pult.grandma.service.PultService.PREF_BANKID_PIN, pinInput.text.toString().filter(Char::isDigit))
                    .putString(ru.pult.grandma.service.PultService.PREF_LOCK_PIN, lockPinInput.text.toString())
                    .apply()
                android.widget.Toast.makeText(this, "PIN saved", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Разовый паринг wireless ADB: порт и код из системного диалога
            // (Настройки → Для разработчиков → Беспроводная отладка → сопряжение по коду).
            // После него наш ключ авторизован у adbd навсегда — shell без компьютера.
            val pairPort = findViewById<android.widget.EditText>(R.id.pairPort)
            val pairCode = findViewById<android.widget.EditText>(R.id.pairCode)
            val pairBtn = findViewById<android.widget.Button>(R.id.pairBtn)
            val pairStatus = findViewById<android.widget.TextView>(R.id.pairStatus)
            pairBtn.setOnClickListener {
                val port = pairPort.text.toString().toIntOrNull()
                val code = pairCode.text.toString().filter(Char::isDigit)
                if (port == null || code.length != 6) {
                    pairStatus.text = "Нужны порт и 6-значный код"
                    return@setOnClickListener
                }
                pairStatus.text = "Pairing…"
                Thread {
                    val ok = ru.pult.grandma.control.AdbShell.pair(port, code)
                    runOnUiThread {
                        pairStatus.text = if (ok) "Сопряжено ✓" else "Не вышло — код одноразовый, открой диалог заново"
                    }
                }.start()
            }
        } else {
            pinInput.visibility = android.view.View.GONE
            pinSave.visibility = android.view.View.GONE
            lockPinInput.visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.pairPort).visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.pairCode).visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.pairBtn).visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.pairStatus).visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // В демо-сборке не гоняем через мастер — APK должен открываться сразу рабочим.
        if (!ru.pult.grandma.BuildConfig.DEMO_MODE && !survivalReady()) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        render()
    }

    /** Критичные для выживания разрешения, которые можно проверить программно. */
    private fun survivalReady(): Boolean =
        BrandSurvival.isBatteryOptimizationIgnored(this) &&
            Settings.canDrawOverlays(this) &&
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun render() {
        val pair = EncryptedPairStore(this).load()
        findViewById<TextView>(R.id.helper).text =
            if (pair == null) getString(R.string.main_no_pair)
            else getString(R.string.main_helper, pair.peerName)
    }
}
