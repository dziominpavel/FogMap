package ru.fogmap

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import ru.fogmap.data.AppContainer
import ru.fogmap.tracking.TrackingWatchdogWorker

class FogMapApp : Application() {
    lateinit var container: AppContainer
        private set

    /** true, только когда задан настоящий ключ и MapKit проинициализирован. */
    var isMapKitReady: Boolean = false
        private set

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
        // Watchdog трекинга (tracking-reliability 2.2): переживает убийство процесса.
        // Application.onCreate выполняется в любом процессе приложения, включая
        // процесс воркеров, — расписание KEEP идемпотентно.
        runCatching { TrackingWatchdogWorker.schedule(this) }
    }
}
