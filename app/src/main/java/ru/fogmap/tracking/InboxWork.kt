package ru.fogmap.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.fogmap.FogMapApp
import ru.fogmap.diag.DevLog

/**
 * Заявка на разбор журнала после приёма фиксов (fix-journal D2).
 *
 * Ресивер не имеет права поднимать foreground-сервис из фона, поэтому он
 * ставит expedited-работу: она поднимает сервис штатным watchdog-путём
 * (пауза, разрешения, Play Services проверяются там же). Если работа не
 * запустилась — фиксы не теряются: их разберёт следующий тик сервиса.
 */
object InboxWork {

    const val NAME = "inbox-process"

    fun enqueue(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<InboxProcessWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure {
            DevLog.w("TRACK", "inbox_work_failed", mapOf("err" to it.javaClass.simpleName))
        }
    }
}

/** Точка входа WorkManager: поднять запись, чтобы журнал разобрался. */
class InboxProcessWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (applicationContext !is FogMapApp) return Result.failure()
        TrackingWatchdog.checkAndStart(applicationContext, source = "inbox")
        return Result.success()
    }
}
