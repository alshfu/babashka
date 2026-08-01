package ru.pult.grandma.vpn

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Конфиг туннеля: адрес прокси и ключ. Хранится ШИФРОВАННО (как секрет пары) — ключ туннеля
 * не должен лежать в открытом виде. Обновляется из внешнего подписанного источника
 * («скажем приложению, где сегодня брать связь»), проверка подписи — при приёме, не здесь.
 *
 * Стадия 2 хранит и выдаёт конфиг; сам разбор vless/vmess и подключение — движок (стадия 3).
 */
class TunnelConfig private constructor(
    val uri: String,
) {
    fun isEmpty() = uri.isBlank()

    companion object {
        private const val PREFS = "pult_tunnel_secure"
        private const val KEY_URI = "config_uri"

        fun load(context: Context): TunnelConfig = TunnelConfig(prefs(context).getString(KEY_URI, "") ?: "")

        fun save(context: Context, uri: String) {
            prefs(context).edit().putString(KEY_URI, uri.trim()).apply()
        }

        private fun prefs(context: Context) = EncryptedSharedPreferences.create(
            context,
            PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
