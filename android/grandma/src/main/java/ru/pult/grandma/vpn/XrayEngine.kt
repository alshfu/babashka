package ru.pult.grandma.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import ru.pult.core.vpn.XrayConfig
import java.io.File

/**
 * Движок туннеля на Xray-core (стадия 3).
 *
 * ЧТО ГОТОВО ЗДЕСЬ (проверяемо): взять ссылку из конфига → построить JSON Xray → записать
 * в файл, и вся обвязка запуска/остановки, включая связку с tun2socks.
 *
 * ЧТО ТРЕБУЕТ ВНЕШНЕГО (пометки TODO): два нативных куска, которых нет в песочнице и которые
 * нельзя проверить без реального сервера —
 *   1. **Xray-core AAR** (напр. `com.github.2dust:AndroidLibXrayLite`) — поднимает прокси
 *      по нашему JSON и слушает локальный SOCKS (XrayConfig.LOCAL_SOCKS_PORT).
 *   2. **tun2socks** (напр. hev-socks5-tunnel .so) — читает пакеты из TUN и гонит в этот SOCKS.
 *
 * Пока AAR не добавлен, `isReady()` = false: PultVpnService НЕ поднимает мёртвый туннель и
 * приложения не теряют сеть. Как только зависимость на месте — движок «загорается».
 */
class XrayEngine(private val context: Context) : TunnelEngine {

    @Volatile private var running = false

    /**
     * Готов, только если нативная библиотека Xray реально присутствует в сборке. Проверяем
     * рефлексией, чтобы модуль КОМПИЛИРОВАЛСЯ без AAR (его добавят отдельным шагом).
     */
    override fun isReady(): Boolean =
        runCatching { Class.forName(LIBXRAY_CLASS); true }.getOrDefault(false)

    override fun start(tun: ParcelFileDescriptor, config: TunnelConfig, protect: (Int) -> Boolean) {
        if (running) return
        if (config.isEmpty()) { Log.w(TAG, "конфиг пуст"); return }

        // 1) Ссылка → JSON Xray (это готово и оттестировано).
        val server = runCatching { XrayConfig.parse(config.uri) }.getOrElse {
            Log.w(TAG, "ссылка не разобрана: ${it.message}"); return
        }
        val configFile = File(context.filesDir, "xray.json").apply {
            writeText(XrayConfig.toXrayJson(server))
        }
        Log.i(TAG, "конфиг записан: ${configFile.name}, сервер ${server.host}:${server.port} (${server.protocol}/${server.network}/${server.security})")

        // 2) Запустить Xray-core по конфигу.
        // TODO(stage3): подключить AAR и вызвать, напр.:
        //   LibXray.initXray(context.filesDir.path); LibXray.runXray(configFile.path)
        //   Xray поднимет SOCKS на 127.0.0.1:${XrayConfig.LOCAL_SOCKS_PORT}.

        // 3) Запустить tun2socks: TUN → SOCKS. Сокеты движка защитить через protect(fd),
        //    иначе трафик прокси зациклится обратно в туннель.
        // TODO(stage3): стартовать tun2socks на tun.fd в сторону 127.0.0.1:${LOCAL_SOCKS_PORT}.

        running = true
        Log.w(TAG, "XrayEngine.start: JSON готов; нативный Xray/tun2socks не подключены — см. TODO(stage3)")
    }

    override fun stop() {
        if (!running) return
        // TODO(stage3): LibXray.stopXray(); остановить tun2socks.
        running = false
    }

    private companion object {
        const val TAG = "PultXray"
        // Класс из AAR AndroidLibXrayLite (v2rayNG). Меняется под конкретную зависимость.
        const val LIBXRAY_CLASS = "go.Seq" // заглушка-маркер: реальный класс из выбранного AAR
    }
}
