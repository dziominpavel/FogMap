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
import ru.fogmap.diag.DevLog
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
 *
 * Каждый цикл оставляет ровно одно событие `TRACK/watchdog` с исходом
 * (diag-start-failures): без него молчаливая неудача перезапуска неотличима
 * от «сервис был жив и просто не писал».
 */
class TrackingWatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FogMapApp ?: return Result.failure()
        val paused = runCatching {
            app.container.dataStore.data.first()[PrefsKeys.PAUSED] ?: false
        }.getOrDefault(false)
        if (paused) {
            report("skip_paused")
            return Result.success()
        }
        if (!TrackingPreconditions.playServicesAvailable(applicationContext)) {
            report("skip_play_services")
            return Result.success()
        }
        if (!TrackingService.canTrack(applicationContext)) {
            report("skip_no_permission")
            return Result.success()
        }
        val ok = TrackingService.start(applicationContext, via = "watchdog")
        // Отказ `start()` уже записан в `start_attempt` с классом/текстом
        // ошибки — здесь только исход цикла, чтобы событие watchdog было одно.
        report(if (ok) "started" else "start_failed")
        return Result.success()
    }

    private fun report(outcome: String) {
        val payload = mapOf("outcome" to outcome, "period_m" to PERIOD_MIN)
        if (outcome == "start_failed") DevLog.w("TRACK", "watchdog", payload)
        else DevLog.i("TRACK", "watchdog", payload)
    }

    companion object {
        const val WORK_NAME = "tracking-watchdog"
        const val PERIOD_MIN = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingWatchdogWorker>(
                PERIOD_MIN, TimeUnit.MINUTES
            ).build()
            val err = runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            }.exceptionOrNull()
            if (err == null) {
                DevLog.i("TRACK", "watchdog_schedule", mapOf("outcome" to "scheduled"))
            } else {
                DevLog.w(
                    "TRACK", "watchdog_schedule",
                    mapOf(
                        "outcome" to "schedule_failed",
                        "err_class" to err.javaClass.simpleName,
                        "err_msg" to (err.message ?: "").take(200)
                    )
                )
            }
        }
    }
}
