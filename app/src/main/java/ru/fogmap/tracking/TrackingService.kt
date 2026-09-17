package ru.fogmap.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.MainActivity
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.RawPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фоновый трекинг (spec tracking, задачи 3.1–3.3):
 * ForegroundService(type=location) → Fused 5–10 сек / 10–20 м →
 * фильтры → батч-буфер → Room-транзакция → Flow-обновление UI.
 * Пауза — флаг в DataStore, авторестарт — BootReceiver.
 */
class TrackingService : LifecycleService() {

    private val buffer = mutableListOf<RawPoint>()
    private var trackId: Long = -1
    private var flushJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onRawLocation(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Без разрешений или Play Services сервис не имеет права на FGS type=location:
        // тихо останавливаемся, НЕ роняем процесс (проверено на эмуляторе API 36).
        if (!TrackingPreconditions.playServicesAvailable(this) || !canTrack(this)) {
            stopSelf()
            return
        }
        try {
            startForegroundCompat(buildNotification(paused = false))
        } catch (e: RuntimeException) {
            stopSelf()
            return
        }
        lifecycleScope.launch {
            val container = (application as FogMapApp).container
            // Подписка на паузу: при включенной паузе точки не пишем.
            launch {
                container.dataStore.data.collect { prefs ->
                    val paused = prefs[PrefsKeys.PAUSED] ?: false
                    updateNotification(paused)
                }
            }
            // Старт трека и Fused-подписка (предусловия уже проверены выше).
            trackId = container.trackRepository.startTrack(
                SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date())
            )
            subscribeFused()
            flushJob = launch {
                while (true) {
                    delay(30_000)
                    flush()
                }
            }
        }
    }

    private fun onRawLocation(loc: Location) {
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
        val ok = LocationFilter.accept(
            LocationFilter.Input(
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                isMock = isMock
            )
        )
        if (!ok) return
        lifecycleScope.launch {
            val paused = (application as FogMapApp).container
                .dataStore.data.first()[PrefsKeys.PAUSED] ?: false
            if (paused) return@launch
            synchronized(buffer) {
                buffer.add(
                    RawPoint(
                        time = loc.time, lat = loc.latitude, lon = loc.longitude,
                        acc = loc.accuracy, speed = if (loc.hasSpeed()) loc.speed else null
                    )
                )
                if (buffer.size >= 20) launch { flush() }
            }
        }
    }

    private suspend fun flush() {
        val batch: List<RawPoint> = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        val container = (application as FogMapApp).container
        runCatching { container.fogRepository.appendPoints(trackId, batch) }
    }

    private fun subscribeFused() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 8_000
        )
            .setMinUpdateIntervalMillis(5_000)
            .setMinUpdateDistanceMeters(15f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Перезапуск системы без разрешений (фон) — не липнем, чтобы не крутить краш-цикл.
        if (!TrackingPreconditions.playServicesAvailable(this) || !canTrack(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            lifecycleScope.launch {
                val container = (application as FogMapApp).container
                val cur = container.dataStore.data.first()[PrefsKeys.PAUSED] ?: false
                container.settingsRepository.setPaused(!cur)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(callback)
        }
        flushJob?.cancel()
        super.onDestroy()
    }

    // --- Уведомление «пишет трек» с действием паузы ---

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Трекинг", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "FogMap на паузе" else "FogMap пишет трек")
            .setContentText(if (paused) "Нажмите, чтобы продолжить запись" else "Туман открывается")
            .setSmallIcon(R.drawable.ic_stat_fog)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "Продолжить" else "Пауза", toggle
            )
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(paused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(paused))
    }

    companion object {
        const val CHANNEL = "tracking"
        const val NOTIF_ID = 41
        const val ACTION_TOGGLE_PAUSE = "ru.fogmap.TOGGLE_PAUSE"

        /** Трекинг разрешен, только когда есть гео-разрешение (spec: «когда разрешено»). */
        fun canTrack(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!canTrack(context)) return
            val i = Intent(context, TrackingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                else context.startService(i)
            }
        }
    }
}
