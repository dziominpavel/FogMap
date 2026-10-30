package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import ru.fogmap.diag.DevLog

/**
 * Авторестарт трекинга после ребута и после обновления приложения
 * (spec tracking, задача 3.3 + change `restart-after-update`).
 * Сам рестарт выполняет [BootWorker] (expedited): прямой старт FGS из ресивера
 * запрещен из фона на новых API, плюс процесс ресивера могут убить раньше,
 * чем отработает корутина, — поэтому здесь только быстрый синхронный код
 * в окне temp-allowlist и постановка задачи.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val via = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> VIA_BOOT
            // После обновления пакета системный broadcast даёт то же
            // временное окно, что и BOOT_COMPLETED: трекинг поднимается
            // без открытия приложения, не дожидаясь watchdog.
            Intent.ACTION_MY_PACKAGE_REPLACED -> VIA_UPDATE
            else -> return
        }
        // Быстрый путь: синхронный старт в окне temp-allowlist (~20 с).
        // Позже окно закрывается и старт location-FGS из фона запрещен (API 34+),
        // поэтому никаких корутин здесь — только быстрый синхронный код.
        runCatching {
            val paused = runBlocking {
                withTimeoutOrNull(3000L) {
                    val app = context.applicationContext as? FogMapApp
                    app?.container?.dataStore?.data?.first()?.get(PrefsKeys.PAUSED)
                }
            } ?: false
            when {
                paused -> DevLog.i(
                    "TRACK", "restart_skip",
                    mapOf("via" to via, "reason" to "paused")
                )
                !TrackingPreconditions.playServicesAvailable(context) -> DevLog.w(
                    "TRACK", "restart_skip",
                    mapOf("via" to via, "reason" to "no_play_services")
                )
                !TrackingService.canTrack(context) -> DevLog.w(
                    "TRACK", "restart_skip",
                    mapOf("via" to via, "reason" to "no_permission")
                )
                else -> TrackingService.start(context, via = via)
            }
        }.onFailure {
            // Отказы самого start() уже записаны в start_attempt; сюда
            // попадают только ошибки чтения DataStore и проверок до него.
            DevLog.w(
                "TRACK", "restart_failed",
                mapOf(
                    "via" to via,
                    "err_class" to it.javaClass.simpleName,
                    "err_msg" to (it.message ?: "").take(200)
                )
            )
        }
        // Запасной путь: expedited-Worker (бесшумный рестарт, если планировщик
        // успеет в окно; иначе трекинг подхватит MapScreen при открытии приложения).
        val request = OneTimeWorkRequestBuilder<BootWorker>()
            .setInputData(workDataOf(BootWorker.KEY_VIA to via))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            DevLog.w(
                "TRACK", "restart_worker_schedule",
                mapOf(
                    "via" to via,
                    "outcome" to "schedule_failed",
                    "err_class" to it.javaClass.simpleName,
                    "err_msg" to (it.message ?: "").take(200)
                )
            )
        }
    }

    companion object {
        const val VIA_BOOT = "boot"
        const val VIA_UPDATE = "update"
        private const val WORK_NAME = "boot-restart"
    }
}
