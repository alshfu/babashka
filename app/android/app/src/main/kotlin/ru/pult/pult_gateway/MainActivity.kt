package ru.pult.pult_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {

    private var linkEvents: MethodChannel? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            linkEvents?.invokeMethod("changed", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this)
        handleBankIdIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter(KeepAliveService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter(KeepAliveService.ACTION_STATUS))
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleBankIdIntent(intent)
    }

    /// Swedbank открывает BankID явным компонентом и NO_HISTORY: activity умирает
    /// сразу после перехвата, поэтому URL сразу передаём в сервисный сокет —
    /// движок к этому моменту может быть уже мёртв.
    private fun handleBankIdIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val url = normalizeBankIdUrl(uri) ?: return
        // Устройство выбирается во Flutter-UI; deeplink без выбора уходит на последнее/единственное.
        val deviceId = prefs().getString("flutter.selectedDeviceId", null)
        KeepAliveService.sendDeeplink(this, url, deviceId)
        moveTaskToBack(true)
    }

    private fun normalizeBankIdUrl(uri: Uri): String? {
        if (uri.scheme == "bankid") return uri.toString()
        if (uri.scheme == "https" && uri.host == "app.bankid.com") {
            val path = uri.path.orEmpty().removePrefix("/")
            val query = uri.encodedQuery?.let { "?$it" } ?: ""
            return "bankid:///$path$query"
        }
        return null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, CONTROL_CHANNEL).setMethodCallHandler { call, result ->
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
                // Список A-app устройств с сервера. Сеть — только не на UI-потоке.
                "getDevices" -> {
                    Thread {
                        val array = KeepAliveService.fetchDevices(this)
                        runOnUiThread { result.success(array.toString()) }
                    }.start()
                }
                // Онбординг: кто ловит bankid://. Если не мы — Swedbank-ссылка
                // уходит в никуда (ActivityNotFound) и вход не инициируется.
                "isDefaultLinkHandler" -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bankid:///"))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    val resolved = runCatching {
                        if (Build.VERSION.SDK_INT >= 33) {
                            packageManager.resolveActivity(
                                intent,
                                android.content.pm.PackageManager.ResolveInfoFlags.of(0),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.resolveActivity(intent, 0)
                        }
                    }.getOrNull()
                    result.success(resolved?.activityInfo?.packageName == packageName)
                }
                // Экран «Открывать по умолчанию» нашего приложения (API 31+).
                "openLinkSettings" -> {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 31) {
                            startActivity(
                                Intent(
                                    "android.settings.APP_OPEN_BY_DEFAULT_SETTINGS",
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        } else {
                            startActivity(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
                        }
                        result.success(true)
                    }.onFailure { result.success(false) }
                }
                else -> result.notImplemented()
            }
        }
        // Состояние /link-канала: Flutter больше не держит свой сокет, он в сервисе.
        linkEvents = MethodChannel(messenger, LINK_CHANNEL)
        linkEvents?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getState" -> {
                    val prefs = prefs()
                    result.success(
                        mapOf(
                            "online" to prefs.getBoolean("linkOnline", false),
                            "lastStatus" to (prefs.getString("lastStatus", "") ?: ""),
                            // Нативный туннель (в сервисе, не в движке).
                            "tunnelOnline" to prefs.getBoolean("flutter.tunnelOnline", false),
                            "tunnelStreams" to prefs.getInt("flutter.tunnelStreams", 0),
                        ),
                    )
                }
                "reloadLink" -> {
                    KeepAliveService.start(this)
                    result.success(true)
                }
                "clearStatus" -> {
                    prefs().edit().remove("lastStatus").apply()
                    result.success(true)
                }
                "sendScreencast" -> {
                    val payload = JSONObject()
                        .put("t", "screencast")
                        .put("on", call.arguments == true)
                        .toString()
                    KeepAliveService.sendJson(this, payload)
                    result.success(true)
                }
                // Ручная отправка диплинка из UI (то же «свой канал» B→A, что и
                // перехват bankid:// от Swedbank): URL валидируем и уходим в сервис.
                "sendDeeplink" -> {
                    val url = call.arguments as? String
                    if (url != null && url.startsWith("bankid:///")) {
                        val deviceId = prefs().getString("flutter.selectedDeviceId", null)
                        KeepAliveService.sendDeeplink(this, url, deviceId)
                    }
                    result.success(true)
                }
                // Сброс BankID-PIN: на далёком телефоне поднимется экран ввода PIN
                // (+ skärmdelning) — вводит человек у телефона, итог вернётся статусом.
                "sendPinSetup" -> {
                    KeepAliveService.sendJson(this, JSONObject().put("t", "pin-setup").toString())
                    result.success(true)
                }
                // Удалённая запись PIN, введённого владельцем в этом приложении:
                // телефон сохранит его локально. PIN — секрет, в логи не пишется.
                "sendPinSet" -> {
                    val pin = call.arguments as? String
                    if (pin != null && pin.matches(Regex("\\d{4,8}"))) {
                        KeepAliveService.sendJson(
                            this,
                            JSONObject().put("t", "pin-set").put("pin", pin).toString(),
                        )
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun prefs() = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private companion object {
        const val CONTROL_CHANNEL = "pult.gateway/control"
        const val LINK_CHANNEL = "pult.gateway/link"
    }
}
