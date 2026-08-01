package ru.pult.grandma.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Сторож сервиса.
 *
 * Xiaomi, Huawei, Oppo и прочие убивают фон без предупреждения. Молчаливая смерть сервиса
 * выглядит как «бабушка не в сети» — и семья узнаёт об этом ровно тогда, когда помощь
 * понадобилась. Поэтому: раз в 15 минут проверяем, что сервис жив, и поднимаем заново
 * (docs/android-grandma.md §3).
 */
class ServiceWatchdog(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        PultService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "pult-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
