package ru.pult.core.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.pult.core.protocol.PairAuth

/**
 * Секрет пары в `EncryptedSharedPreferences` поверх Android Keystore.
 *
 * `setUserAuthenticationRequired` намеренно НЕ включаем: сервис бабушки должен работать
 * и до разблокировки экрана, иначе помощь недоступна ровно тогда, когда она нужна.
 * Компромисс осознанный — от кражи разблокированного телефона защищает не это,
 * а отзыв пары со второго устройства (docs/pairing.md §4.2).
 *
 * Файл исключён из бэкапов (`allowBackup=false` + `dataExtractionRules`), поэтому секрет
 * не уезжает ни в облако, ни в `adb backup`.
 */
class EncryptedPairStore(context: Context) : PairStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): PairRecord? {
        val pairId = prefs.getString(KEY_PAIR_ID, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        return PairRecord(
            pairId = pairId,
            secret = PairAuth.fromBase64url(secret),
            peerName = prefs.getString(KEY_NAME, null) ?: "Помощник",
            signalingUrl = prefs.getString(KEY_URL, null).orEmpty(),
            createdAt = prefs.getLong(KEY_CREATED, 0L),
        )
    }

    override fun save(record: PairRecord) {
        prefs.edit()
            .putString(KEY_PAIR_ID, record.pairId)
            .putString(KEY_SECRET, PairAuth.base64url(record.secret))
            .putString(KEY_NAME, record.peerName)
            .putString(KEY_URL, record.signalingUrl)
            .putLong(KEY_CREATED, record.createdAt)
            .commit() // именно commit: пара должна быть на диске до того, как мы скажем «готово»
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE_NAME = "pult_pair"
        const val KEY_PAIR_ID = "pair_id"
        const val KEY_SECRET = "secret"
        const val KEY_NAME = "peer_name"
        const val KEY_URL = "signaling_url"
        const val KEY_CREATED = "created_at"
    }
}
