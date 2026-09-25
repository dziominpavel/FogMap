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
import ru.fogmap.tracking.TrackingWatchdogWorker

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
        // ВРЕМЕННОЕ (dev-logging): старт файлового лога + TTL-чистка 24ч на фоне.
        // Тумблер DIAG_ENABLED решает, пишется ли новое; старые файлы чистим всегда.
        val devFile = DevLogFile(this)
        devLogFile = devFile
        devFile.start()
        appScope.launch {
            val removed = runCatching { devFile.cleanupOlderThan24h() }.getOrDefault(0)
            val enabled = runCatching {
                container.dataStore.data.first()[PrefsKeys.DIAG_ENABLED] ?: true
            }.getOrDefault(true)
            DevLog.enabled = enabled
            // Разовый ремонт ложных veto_return (fix-pending-gate-false-vetoes
            // E): стартует сразу при старте приложения, пока БД уже собрана в
            // AppContainer, а карта только открывается. Один раз за жизнь БД
            // (флаг в counters), идемпотентен; ошибка не роняет старт — флаг
            // не запишется и проход повторится в следующий запуск.
            runCatching { container.fogRepository.repairVetoed() }
                .onSuccess { s ->
                    if (s != null) {
                        DevLog.i(
                            "TRACK", "pending_gate_repaired",
                            mapOf("tracks" to s.tracks, "points" to s.points, "cells" to s.cells)
                        )
                    }
                }
                .onFailure {
                    DevLog.w(
                        "TRACK", "pending_gate_repair_failed",
                        mapOf("err" to (it.message ?: it::class.java.simpleName))
                    )
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
        // Application.onCreate выполняется в любом процессе приложения, включая
        // процесс воркеров, — расписание KEEP идемпотентно.
        runCatching { TrackingWatchdogWorker.schedule(this) }
    }
}
