package ru.pult.grandma.lowlat

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Точка запуска прототипа низколатентной трансляции: просит согласие на захват экрана и
 * стартует [LowLatService]. Отдельно от обычного показа — это стенд для замера задержки.
 *
 * Запуск (демо): `adb shell am start -n se.pult.app/ru.pult.grandma.lowlat.LowLatActivity`.
 * Битрейт/fps и адрес релея можно переопределить экстрами (для узкого канала через VPS):
 * `adb shell am start -n se.pult.app/ru.pult.grandma.lowlat.LowLatActivity \
 *    --ei bitrate 1500000 --ei fps 20 \
 *    --es url 'wss://85.190.98.57.sslip.io:8445/lowlat?room=<deviceId>&role=device'`.
 * Комната = deviceId устройства: в паре несколько A-app, у каждой своя трансляция.
 * Удобная обёртка на время разработки: `node scripts/lowlat-start.mjs`.
 */
class LowLatActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Трансляция идёт, пока жив захват: гаснущий экран роняет и её.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            LowLatService.start(
                this, resultCode, data,
                bitrate = intent.getIntExtra(LowLatService.EXTRA_BITRATE, 0),
                fps = intent.getIntExtra(LowLatService.EXTRA_FPS, 0),
                url = intent.getStringExtra(LowLatService.EXTRA_URL),
            )
        }
        finish()
    }

    private companion object {
        const val REQ = 7001
    }
}
