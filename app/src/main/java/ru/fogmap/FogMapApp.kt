package ru.fogmap

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.fogmap.data.AppContainer
import ru.fogmap.data.PrefsKeys
import ru.fogmap.diag.DevLog
import ru.fogmap.diag.DevLogFile
import ru.fogmap.tracking.TrackingLife
import ru.fogmap.tracking.TrackingWatchdog

class FogMapApp : Application() {
    lateinit var container: AppContainer
        private set

    /** true, только когда задан настоящий ключ и MapKit проинициализирован. */
    var isMapKitReady: Boolean = false
        private set

    /** ВРЕМЕННОЕ (dev-logging): файловый writer диагностики. */
    var devLogFile: DevLogFile? = null
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // MapKit: ключ вне git (local.properties -> BuildConfig).
        val apiKey = BuildConfig.MAPKIT_API_KEY
        if (apiKey.isNotBlank() && apiKey != "YOUR_API_KEY") {
            runCatching {
                MapKitFactory.setApiKey(apiKey)
                MapKitFactory.initialize(this)
                isMapKitReady = true
            }
        }
        // Без ключа карту не создаем: экраны покажут заглушку (проверено —
        // MapView без ключа роняет процесс AssertionError).
        container = AppContainer(this)
        // ВРЕМЕННОЕ (dev-logging): старт файлового лога + TTL-чистка 7 дней на фоне.
        // Тумблер DIAG_ENABLED решает, пишется ли новое; старые файлы чистим всегда.
        val devFile = DevLogFile(this)
        devLogFile = devFile
        devFile.start()
        // Канал жизни процесса (fix-track-gap-visibility 1.3): `proc_start` —
        // первое событие процесса, до любых других записей. Метка прошлой
        // сессии переживает убийство процесса, поэтому разрыв виден числом.
        runCatching { TrackingLife.onProcessStart(this) }
        appScope.launch {
            val removed = runCatching { devFile.cleanupExpired() }.getOrDefault(0)
            val enabled = runCatching {
                container.dataStore.data.first()[PrefsKeys.DIAG_ENABLED] ?: true
            }.getOrDefault(true)
            DevLog.enabled = enabled
            // Снятые ключи эко-режима не должны переживать обновление (8.3).
            runCatching { container.settingsRepository.cleanLegacyEcoKeys() }
                .onSuccess { removed ->
                    if (removed > 0) {
                        DevLog.i("TRACK", "legacy_prefs_cleaned", mapOf("removed" to removed))
                    }
                }
            // Подписка на тумблер на весь процесс.
            runCatching {
                container.dataStore.data.collect { prefs ->
                    DevLog.enabled = prefs[PrefsKeys.DIAG_ENABLED] ?: true
                }
            }
            DevLog.i("DevApp", "diag_init", mapOf("enabled" to enabled, "ttl_removed" to removed))
        }
        // Watchdog трекинга (tracking-reliability 2.2): переживает убийство процесса.
        // Два канала (fix-track-reliability-0928): периодическая работа + будильник.
        // Application.onCreate выполняется в любом процессе приложения, включая
        // процесс воркеров, — расписание KEEP идемпотентно.
        // Исход внутри scheduleAll() пишет сам; onFailure страхует от отказа,
        // случившегося до внутреннего runCatching (diag-start-failures 1.5).
        runCatching { TrackingWatchdog.scheduleAll(this) }
            .onFailure {
                DevLog.w(
                    "TRACK", "watchdog_schedule",
                    mapOf(
                        "outcome" to "schedule_failed",
                        "err_class" to it.javaClass.simpleName,
                        "err_msg" to (it.message ?: "").take(200)
                    )
                )
            }
    }
}
