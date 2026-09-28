package ru.fogmap.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.fogmap.FogMapApp

/**
 * Канал «работа» watchdog живости трекинга (tracking-reliability 2.2).
 *
 * Сама проверка вынесена в [TrackingWatchdog.checkAndStart] — ее же выполняет
 * второй канал ([WatchdogAlarmReceiver]), чтобы условия пропуска не расходились.
 * Эта обертка нужна только как точка входа WorkManager; планирование —
 * [TrackingWatchdog.scheduleAll].
 *
 * Каждый цикл оставляет ровно одно событие `TRACK/watchdog` с исходом
 * (diag-start-failures): без него молчаливая неудача перезапуска неотличима
 * от «сервис был жив и просто не писал».
 */
class TrackingWatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (applicationContext !is FogMapApp) return Result.failure()
        TrackingWatchdog.checkAndStart(applicationContext, source = "work")
        return Result.success()
    }
}
