package ru.pult.grandma.ui

import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.BuildConfig

/**
 * Роль «Панель» в едином приложении. Показывает проверенную веб-панель во встроенном WebView:
 * та же панель, что открывается в браузере (трансляция, указатель, слайды-обучение, управление).
 *
 * Почему WebView, а не нативный экран: панель уже реализована и обкатана на вебе, WebRTC/WSS
 * там работают. Единая сборка получает полноценную панель без переписывания UI заново — это
 * ровно «делаем то, что можем» из ТЗ all-in-one.
 */
class PanelActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // уроки/черновики в localStorage
            settings.mediaPlaybackRequiresUserGesture = false // видео от бабушки играет само
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // Панель только ПРИНИМАЕТ экран бабушки; лишнего не просит. Что запросит — даём.
                    runOnUiThread { request.grant(request.resources) }
                }
            }
        }
        web.loadUrl(panelUrl())
        setContentView(web)
    }

    /** Адрес панели выводим из адреса сигналинга сборки. */
    private fun panelUrl(): String {
        val base = BuildConfig.DEMO_SIGNALING_URL
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/ws")
        return "$base/panel/?role=helper&demo=1"
    }
}
