package ru.pult.pult_gateway

import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                // Глобальный HTTP-прокси на наш локальный порт = весь трафик через туннель.
                "setProxy" -> {
                    val on = call.argument<Boolean>("on") ?: false
                    val port = call.argument<Int>("port") ?: 8877
                    try {
                        Settings.Global.putString(
                            contentResolver, Settings.Global.HTTP_PROXY,
                            if (on) "127.0.0.1:$port" else ":0",
                        )
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("proxy", e.message, null)
                    }
                }
                "getProxy" -> result.success(
                    Settings.Global.getString(contentResolver, Settings.Global.HTTP_PROXY),
                )
                else -> result.notImplemented()
            }
        }
    }

    private companion object {
        const val CONTROL_CHANNEL = "pult.gateway/control"
    }
}
