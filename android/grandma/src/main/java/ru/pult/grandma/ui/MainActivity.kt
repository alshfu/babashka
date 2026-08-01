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
