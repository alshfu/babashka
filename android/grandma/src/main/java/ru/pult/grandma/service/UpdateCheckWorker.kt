package ru.pult.grandma.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.grandma.net.UpdateManager
import java.util.concurrent.TimeUnit

/**
 * Периодическая проверка обновлений (дополняет мгновенный пуш update-available):
 * раз в 6 ч спрашивает у сервера манифест по каждому виду (apk, dex). Статус попытки
 * складывается в отложенный — PultService отдаёт его при следующем подключении.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Пары нет — сервера обновлений у устройства нет, проверять нечего.
        val pair = EncryptedPairStore(applicationContext).load() ?: return Result.success()
        UpdateManager(applicationContext).checkPeriodic(pair.signalingUrl)
        return Result.success()
    }

    companion object {
        private const val NAME = "pult-update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
