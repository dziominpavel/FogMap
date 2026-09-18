package ru.fogmap.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys

/**
 * Перезапуск трекинга после ребута (задача 3.3).
 * Прямой startForegroundService из BootReceiver запрещен из фона на API 31+,
 * поэтому рестарт идет санкционированным путем: expedited-Worker + setForeground.
 */
class BootWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "boot-restart: worker started")
        // setForeground — ПЕРВЫМ делом: expedited-задачу, не ушедшую в foreground
        // за ~10 секунд, система останавливает (проверено на эмуляторе: STOP canceled).
        // Проверки разрешений/паузы — только после.
        try {
            setForeground(foregroundInfo())
            Log.i(TAG, "boot-restart: setForeground ok")
        } catch (t: Throwable) {
            Log.w(TAG, "boot-restart: setForeground failed", t)
            return Result.success()
        }
        val app = applicationContext as? FogMapApp ?: return Result.failure()
        val paused = app.container.dataStore.data.first()[PrefsKeys.PAUSED] ?: false
        Log.i(TAG, "boot-restart: paused=$paused")
        // Watchdog на случай будущих убийств (tracking-reliability 2.2).
        // KEEP-политика делает повторное расписание no-op.
        runCatching { TrackingWatchdogWorker.schedule(applicationContext) }
        if (paused) return Result.success()
        val play = TrackingPreconditions.playServicesAvailable(applicationContext)
        val track = TrackingService.canTrack(applicationContext)
        Log.i(TAG, "boot-restart: play=$play canTrack=$track")
        if (!play || !track) return Result.success()
        return try {
            TrackingService.start(applicationContext)
            Log.i(TAG, "boot-restart: TrackingService.start called")
            Result.success()
        } catch (t: Throwable) {
            // Не роняем воркер в цикл: пользователь откроет приложение —
            // MapScreen подхватит трекинг сам.
            Log.w(TAG, "boot-restart: TrackingService.start failed", t)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "FogMapBoot"
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                TrackingService.CHANNEL, "Трекинг", NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(applicationContext, TrackingService.CHANNEL)
            .setContentTitle("FogMap запускает трекинг")
            .setContentText("Восстановление записи после перезагрузки")
            .setSmallIcon(R.drawable.ic_stat_fog)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                TrackingService.NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ForegroundInfo(TrackingService.NOTIF_ID, notification)
        }
    }
}
