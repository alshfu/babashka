package ru.pult.grandma.pin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Säker lokal lagring av BankID-PIN och skärmlås-PIN på A-appen (Readme).
 *
 * PIN-koderna sparas aldrig på B-appen (Note 10) och skickas inte över nätverket.
 * De krypteras med Android Keystore via EncryptedSharedPreferences.
 */
object PinStorage {

    private const val PREFS_FILE = "pult_pin_secure"
    private const val KEY_BANKID_PIN = "bankid_pin"
    private const val KEY_LOCK_PIN = "lock_pin"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getBankIdPin(context: Context): String =
        prefs(context).getString(KEY_BANKID_PIN, "") ?: ""

    fun getLockPin(context: Context): String =
        prefs(context).getString(KEY_LOCK_PIN, "") ?: ""

    fun hasBankIdPin(context: Context): Boolean =
        getBankIdPin(context).isNotEmpty()

    fun saveBankIdPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_BANKID_PIN, pin.filter { it.isDigit() }).apply()
    }

    fun saveLockPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_LOCK_PIN, pin.filter { it.isDigit() }).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_BANKID_PIN).remove(KEY_LOCK_PIN).apply()
    }
}
