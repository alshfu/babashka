package ru.pult.grandma.net

import android.content.Context
import android.util.Log
import ru.pult.core.net.SignedConfig
import ru.pult.grandma.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Автоподхват адресов и ключей из внешнего источника (GitHub raw / CDN).
 *
 * Единственный принцип, на котором всё держится: применяем ТОЛЬКО подписанный манифест.
 * Источник публичный и может быть скомпрометирован — не важно: без валидной подписи запиненным
 * ключом манифест молча отвергается. Плюс защита от отката: манифест старше применённого не берём.
 *
 * Что обновляем: список эндпоинтов сигналинга (EndpointStore).
 * «Скажем приложению, где сегодня брать связь» — но говорим только мы.
 */
class ConfigRefresher(
    private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Тянет и применяет конфиг. Возвращает true, если что-то реально обновилось. */
    fun refresh(): Boolean {
        val url = BuildConfig.CONFIG_SOURCE_URL
        if (url.isBlank()) return false

        val body = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                if (responseCode !in 200..299) null
                else inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
            }
        }.getOrNull() ?: return false

        val manifest = SignedConfig.verify(body, PINNED_PUBLIC_KEY)
        if (manifest == null) {
            Log.w(TAG, "манифест без валидной подписи — отвергнут")
            return false
        }
        if (manifest.updatedAt <= prefs.getLong(KEY_APPLIED, 0L)) {
            return false // защита от отката/повтора
        }

        var changed = false
        if (manifest.endpoints.isNotEmpty()) {
            EndpointStore(context).save(manifest.endpoints)
            changed = true
        }
        prefs.edit().putLong(KEY_APPLIED, manifest.updatedAt).apply()
        Log.i(TAG, "конфиг применён: эндпоинтов ${manifest.endpoints.size}")
        return changed
    }

    private companion object {
        const val TAG = "PultConfig"
        const val PREFS = "pult_config_meta"
        const val KEY_APPLIED = "applied_at"

        /**
         * Запиненный публичный ключ команды (EC P-256, SPKI DER, base64). Приватный ключ —
         * ОФЛАЙН у команды, в приложение не попадает. Подпись проверяется только этим ключом.
         */
        const val PINNED_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEE4lMaiAnXcawkI07IZt2GR4EWb8rFj8Sv" +
                "QWKFFjBv+5rNDFe4jTff2bSEhPzcJLQIGRWn+ZfG6fV+IaNFoZAeQ=="
    }
}
