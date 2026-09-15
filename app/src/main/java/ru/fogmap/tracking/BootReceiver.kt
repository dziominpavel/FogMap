package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys

/**
 * Авторестарт трекинга после ребута (spec tracking, задача 3.3).
 * Сам рестарт выполняет [BootWorker] (expedited): прямой старт FGS из ресивера
 * запрещен из фона на новых API, плюс процесс ресивера могут убить раньше,
 * чем отработает корутина, — поэтому здесь только синхронная постановка задачи.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        // Быстрый путь: синхронный старт в окне temp-allowlist BOOT_COMPLETED (~20 с).
        // Позже окно закрывается и старт location-FGS из фона запрещен (API 34+),
        // поэтому никаких корутин здесь — только быстрый синхронный код.
        runCatching {
            val paused = runBlocking {
                withTimeoutOrNull(3000L) {
                    val app = context.applicationContext as? FogMapApp
                    app?.container?.dataStore?.data?.first()?.get(PrefsKeys.PAUSED)
                }
            } ?: false
            if (!paused &&
                TrackingPreconditions.playServicesAvailable(context) &&
                TrackingService.canTrack(context)
            ) {
                context.startForegroundService(Intent(context, TrackingService::class.java))
            }
        }
        // Запасной путь: expedited-Worker (бесшумный рестарт, если планировщик
        // успеет в окно; иначе трекинг подхватит MapScreen при открытии приложения).
        val request = OneTimeWorkRequestBuilder<BootWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("boot-restart", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
