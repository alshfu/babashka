package ru.pult.grandma.setup

import android.content.Context
import ru.pult.core.pairing.PairRecord
import ru.pult.core.pairing.PairStore
import ru.pult.core.protocol.PairAuth
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.service.PultService

/**
 * Демо-режим: APK «поставил и работает» без спаривания.
 *
 * При первом запуске, если пары ещё нет, подставляем ФИКСИРОВАННУЮ демо-пару — тот же
 * секрет, что зашит в веб-панель (`?demo=1`), — и включаем turnkey. Инвестор просто ставит
 * APK, а помощник тут же может показать работу сервиса.
 *
 * Это сознательно небезопасно (общий фиксированный секрет) и только для демонстрации.
 * Настоящее спаривание с уникальными ключами и безопасной установкой — следующий этап.
 */
object DemoBootstrap {

    // Тот же фиксированный секрет (32 байта 0x07) и pairId, что в web/public/app.js demoPair().
    private const val DEMO_PAIR_ID = "demo-pair-000000000000"
    private val DEMO_SECRET = ByteArray(32) { 7 }

    /** Вызывается на старте приложения. Ничего не делает вне демо-сборки. */
    fun ensurePaired(context: Context, store: PairStore) {
        if (!BuildConfig.DEMO_MODE) return
        if (store.load() != null) return // уже есть пара — не трогаем

        store.save(
            PairRecord(
                pairId = DEMO_PAIR_ID,
                secret = DEMO_SECRET.copyOf(),
                peerName = "Alex",
                signalingUrl = BuildConfig.DEMO_SIGNALING_URL,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // Турнкей: бабушка ничего не нажимает.
        context.getSharedPreferences("pult_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(PultService.PREF_AUTO_ACCEPT, true).apply()
    }

    /** Для сверки: тот же токен журнала, что и у панели. */
    fun demoJournalToken(): String = PairAuth.journalToken(DEMO_SECRET)
}
