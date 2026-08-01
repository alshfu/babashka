package ru.pult.grandma.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.core.pairing.PairRecord
import ru.pult.core.pairing.PairingPacket
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.service.PultService

/**
 * Ввод пары с компьютера разработчика — ТОЛЬКО в отладочной сборке.
 *
 * Живёт в `src/debug`, поэтому в релизный APK не попадает физически, а не «по флажку».
 * Это важно: удалённый способ завести пару — ровно та дыра, которую продукт закрывает
 * (docs/pairing.md). На устройстве пользователя пара создаётся только вблизи.
 *
 *   adb shell am broadcast -a ru.pult.grandma.DEBUG_PAIR \
 *     -n ru.pult.grandma/.debug.DebugPairReceiver --es packet "<пакет base64url>"
 */
class DebugPairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packed = intent.getStringExtra("packet") ?: run {
            Log.w(TAG, "нет extra packet")
            return
        }

        val packet = runCatching { PairingPacket.decode(packed) }.getOrElse { error ->
            Log.e(TAG, "пакет не разобран: ${error.message}")
            return
        }

        // Адрес сигналинга: у эмулятора, у телефона по USB (adb reverse) и у телефона
        // в Wi-Fi он разный, поэтому его можно передать явно.
        val url = intent.getStringExtra("url") ?: BuildConfig.DEFAULT_SIGNALING_URL

        // Отладка турнкей-режима: --ez auto true имитирует настройку «под ключ».
        // В бою этот флаг ставит мастер настройки / Device Owner, а не broadcast.
        if (intent.hasExtra("auto")) {
            context.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_accept", intent.getBooleanExtra("auto", false))
                .apply()
            Log.i(TAG, "auto_accept = ${intent.getBooleanExtra("auto", false)}")
        }

        EncryptedPairStore(context).save(
            PairRecord(
                pairId = packet.pairId,
                secret = packet.secret(),
                peerName = packet.name,
                signalingUrl = url,
                createdAt = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "пара принята: ${packet.pairId.take(8)}… → $url")
        PultService.pairChanged(context)
    }

    private companion object {
        const val TAG = "PultDebugPair"
    }
}
