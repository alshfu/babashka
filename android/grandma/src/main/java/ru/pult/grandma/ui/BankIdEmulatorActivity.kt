package ru.pult.grandma.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.R
import ru.pult.grandma.control.ScenarioRecorder

/**
 * Визуальный эмулятор экрана BankID внутри приложения Pult.
 *
 * Зачем: настоящий BankID закрыт FLAG_SECURE — тапы по нему не снять и не записать.
 * Здесь показывается точная копия экрана (та же разметка, цвета, размеры из APK 7.48.0).
 * Владелец тапает по копии; запись ведёт общий ScenarioRecorder, которым управляет
 * веб-панель (start-recording / stop-recording) или экран сценариев на телефоне.
 *
 * Экран загружается из встроенного HTML-эмулятора (bankid-pin.html).
 */
class BankIdEmulatorActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val recorder = ScenarioRecorder.shared

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bankid_emulator)

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "PultBridge")
        web.loadUrl("file:///android_asset/bankid-pin.html")
    }

    /** Мост из HTML-эмулятора: тап по кнопке → шаг сценария с координатами живого BankID.
     *  JS передаёт центр кнопки в CSS-пикселях WebView; мы переводим их в физические пиксели
     *  (с учётом плотности экрана), прибавляем положение WebView на экране и делим на
     *  разрешение экрана. Получаем доли, совпадающие с живым BankID. */
    inner class Bridge {
        @JavascriptInterface
        fun onKey(key: String, cx: Float, cy: Float) {
            if (!recorder.isRecording) return
            val location = IntArray(2).also { web.getLocationOnScreen(it) }
            val dm = resources.displayMetrics
            val density = dm.density.coerceAtLeast(1f)
            val screenX = location[0] + cx * density
            val screenY = location[1] + cy * density
            val x = screenX / dm.widthPixels
            val y = screenY / dm.heightPixels
            recorder.addCoordinateStep(x, y, pkg = "com.bankid.bus", label = key)
        }
    }
}
