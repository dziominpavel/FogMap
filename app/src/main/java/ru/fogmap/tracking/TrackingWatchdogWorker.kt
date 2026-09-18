package ru.fogmap.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import java.util.concurrent.TimeUnit

/**
 * Watchdog живости трекинга (tracking-reliability 2.2, spec tracking-reliability).
 *
 * Проблема: трекинг стартовал только из MapScreen и boot-пути. Системное убийство
 * процесса днем (Doze, OEM-оптимизация) давало дыру до следующего открытия
 * приложения (кейс 12:00-13:00 -> старт 13:27). START_STICKY один на новых API
 * и агрессивных прошивках не спасает, поэтому поверх — санкционированная
 * периодическая проверка. Пауза respected: при включенной паузе — no-op.
 * Повторный старт уже бегущего сервиса безвреден (onStartCommand).
 */
class TrackingWatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FogMapApp ?: return Result.failure()
        val paused = runCatching {
            app.container.dataStore.data.first()[PrefsKeys.PAUSED] ?: false
        }.getOrDefault(false)
        if (paused) return Result.success()
        if (!TrackingPreconditions.playServicesAvailable(applicationContext)) {
            return Result.success()
        }
        if (!TrackingService.canTrack(applicationContext)) return Result.success()
        TrackingService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "tracking-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            }
        }
    }
}
