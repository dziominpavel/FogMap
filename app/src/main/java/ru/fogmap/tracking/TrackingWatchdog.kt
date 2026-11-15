package ru.fogmap.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import ru.fogmap.diag.DevLog
import java.util.concurrent.TimeUnit

/**
 * Watchdog живости трекинга (tracking-reliability 2.2 + fix-track-reliability-0928).
 *
 * Два независимых канала, потому что один молчал 13 циклов подряд 28.09
 * (11:00-14:05: ни строки DevLog, ни raw-fix): периодическая работа
 * WorkManager и периодический будильник `setAndAllowWhileIdle`, переживающий
 * приостановку jobs. Оба выполняют одну и ту же проверку и пишут одно
 * событие `TRACK/watchdog` с полем `source` (`work` | `alarm`) — по логу
 * видно, сработал хоть один канал.
 *
 * Повторный старт уже бегущего сервиса безвреден (onStartCommand).
 */
object TrackingWatchdog {

    const val WORK_NAME = "tracking-watchdog"
    const val PERIOD_MIN = 15L

    /** Фаза будильника: +7 мин, чтобы два канала не совпадали по времени. */
    const val ALARM_OFFSET_MS = 7 * 60_000L
    private const val ALARM_REQUEST_CODE = 0x7A17

    /**
     * Общая проверка обоих каналов: пауза → Play Services → разрешения →
     * старт сервиса. Возвращает исход для тестов/логов; одно событие
     * `TRACK/watchdog` с исходом и источником. Блокирующая (DataStore читается
     * через `first()`), вызывать только с фонового потока.
     */
    fun checkAndStart(context: Context, source: String): String {
        val app = context.applicationContext as? FogMapApp
        val recording = if (app == null) {
            true
        } else {
            runCatching {
                runBlocking { app.container.settingsRepository.recordingEnabled.first() }
            }.getOrDefault(true)
        }
        val decision = RecordingGate.decide(
            recording = recording,
            playServices = TrackingPreconditions.playServicesAvailable(context),
            canTrack = TrackingService.canTrack(context)
        )
        val ageMs = TrackingHealth.ageMs()
        val outcome = if (decision == RecordingGate.Decision.START) {
            val started = TrackingService.start(context, via = "watchdog")
            when {
                // Отказ `start()` уже записан в `start_attempt` с классом/текстом
                // ошибки — здесь только исход цикла, чтобы событие было одно.
                !started -> "start_failed"
                // Служба жива, а фиксы не идут: «started» здесь было бы ложью —
                // ровно та маскировка, из-за которой суточная дыра не находилась.
                TrackingHealth.serviceAlive && ageMs >= FixSilence.THRESHOLD_MS -> "fix_stale"
                else -> "started"
            }
        } else {
            RecordingGate.outcomeName(decision)
        }
        report(source, outcome, ageMs)
        return outcome
    }

    private fun report(source: String, outcome: String, ageMs: Long) {
        val payload = buildMap {
            put("outcome", outcome)
            put("period_m", PERIOD_MIN)
            put("source", source)
            if (outcome == "fix_stale") put("age_ms", ageMs)
        }
        if (outcome == "start_failed" || outcome == "fix_stale") {
            DevLog.w("TRACK", "watchdog", payload)
        } else {
            DevLog.i("TRACK", "watchdog", payload)
        }
    }

    /** Единая точка планирования: WorkManager + будильник, идемпотентно. */
    fun scheduleAll(context: Context) {
        scheduleWork(context)
        scheduleAlarm(context)
    }

    fun scheduleWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrackingWatchdogWorker>(
            PERIOD_MIN, TimeUnit.MINUTES
        ).build()
        val err = runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }.exceptionOrNull()
        scheduleLog("work", err)
    }

    /**
     * Второй канал: неточный будильник разрешен всегда (в отличие от точного,
     * которому на targetSdk 31+ нужно разрешение), срабатывает и в Doze.
     * Следующий цикл перепланируется из [WatchdogAlarmReceiver].
     */
    fun scheduleAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + ALARM_OFFSET_MS
        val err = runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent(context)
            )
        }.exceptionOrNull()
        scheduleLog("alarm", err)
    }

    private fun scheduleLog(channel: String, err: Throwable?) {
        if (err == null) {
            DevLog.i(
                "TRACK", "watchdog_schedule",
                mapOf("outcome" to "scheduled", "channel" to channel)
            )
        } else {
            DevLog.w(
                "TRACK", "watchdog_schedule",
                mapOf(
                    "outcome" to "schedule_failed",
                    "channel" to channel,
                    "err_class" to err.javaClass.simpleName,
                    "err_msg" to (err.message ?: "").take(200)
                )
            )
        }
    }

    internal fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
