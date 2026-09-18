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
import ru.fogmap.data.FogRepository
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.RawPoint

/**
 * Фоновый трекинг (spec tracking, задачи 3.1–3.3):
 * ForegroundService(type=location) → Fused 5–10 сек / 10–20 м →
 * фильтры → батч-буфер → Room-транзакция → Flow-обновление UI.
 * Пауза — флаг в DataStore, авторестарт — BootReceiver.
 */
class TrackingService : LifecycleService() {

    private val buffer = mutableListOf<RawPoint>()
    /** Отбросы текущей пачки: причина -> count (tracking-reliability 3.1). */
    private val rejected = mutableMapOf<String, Long>()
    private var trackId: Long = -1
    private var flushJob: Job? = null
    /** Дата текущего суток-чанка (trust-v2 3.1): полночь режет чанк. */
    private var chunkDate: java.time.LocalDate = java.time.LocalDate.now()
    /** Момент последней доставки Fused (любой, даже отброшенной). */
    private var lastFixTime: Long = System.currentTimeMillis()
    /** Кэш флага паузы (gps-trust-filter): вердикт и запись — синхронно в колбэке. */
    @Volatile
    private var pausedCached: Boolean = false
    /** Состояние движка доверия + окно истории записанных точек. */
    private var trustPrev: TrustEngine.PrevState? = null
    private val trustHistory = ArrayDeque<TrustEngine.HistPoint>()

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
            // Флаг дублируется в pausedCached, чтобы onRawLocation оставался
            // синхронным (вердикт + запись без гонок между колбэками).
            launch {
                container.dataStore.data.collect { prefs ->
                    val paused = prefs[PrefsKeys.PAUSED] ?: false
                    pausedCached = paused
                    updateNotification(paused)
                }
            }
            // Старт суток-чанка и Fused-подписка (предусловия уже проверены выше).
            trackId = container.trackRepository.startDayChunk()
            chunkDate = java.time.LocalDate.now()
            subscribeFused()
            flushJob = launch {
                while (true) {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    private fun onRawLocation(loc: Location) {
        lastFixTime = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
        val input = LocationFilter.Input(
            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
            speed = if (loc.hasSpeed()) loc.speed else null,
            isMock = isMock
        )
        val reason = LocationFilter.reason(input)
        if (reason != LocationFilter.Reason.OK) {
            synchronized(buffer) {
                rejected[reason.key] = (rejected[reason.key] ?: 0) + 1
            }
            return
        }
        // Доверие по последовательности (gps-trust-filter): вердикт считается
        // синхронно в колбэке, история — только записанные точки.
        val hp = TrustEngine.HistPoint(
            time = loc.time, lat = loc.latitude, lon = loc.longitude, acc = loc.accuracy
        )
        synchronized(buffer) {
            if (pausedCached) {
                rejected[FogRepository.REJECT_PAUSED] =
                    (rejected[FogRepository.REJECT_PAUSED] ?: 0) + 1
                return
            }
            val verdict = TrustEngine.evaluate(trustPrev, trustHistory.toList(), hp)
            trustPrev = verdict.next
            verdict.countReject?.let { key ->
                rejected[key] = (rejected[key] ?: 0) + 1
            }
            // Раздельные ворота (spec tracking): точка пишется всегда
            // с полным вердиктом, туман — только подтвержденным (ворота C).
            buffer.add(
                RawPoint(
                    time = loc.time, lat = loc.latitude, lon = loc.longitude,
                    acc = loc.accuracy, speed = if (loc.hasSpeed()) loc.speed else null,
                    trust = verdict.trust, openFog = verdict.openFog,
                    state = verdict.state.name, rejectReason = verdict.countReject
                )
            )
            trustHistory.addLast(hp)
            while (trustHistory.size > TrustEngine.HISTORY_MAX) trustHistory.removeFirst()
            if (buffer.size >= FLUSH_SIZE) lifecycleScope.launch { flush() }
        }
    }

    private suspend fun flush() {
        // suspend-вызовы — строго вне synchronized (иначе critical section).
        val starved: Boolean = synchronized(buffer) {
            buffer.isEmpty() && rejected.isEmpty()
        }
        if (starved) {
            flushIfStarved()
            return
        }
        val batch: List<RawPoint> = synchronized(buffer) {
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        val rej: Map<String, Long> = synchronized(buffer) {
            val copy = rejected.toMap()
            rejected.clear()
            copy
        }
        val container = (application as FogMapApp).container
        runCatching {
            // Граница суток: новый чанк до записи (коридор через полночь
            // не тянется — у нового трека нет хвоста, честно по spec).
            val today = java.time.LocalDate.now()
            if (today != chunkDate) {
                trackId = container.trackRepository.startDayChunk(today)
                chunkDate = today
            }
            if (batch.isNotEmpty()) container.fogRepository.appendPoints(trackId, batch)
            if (rej.isNotEmpty()) container.fogRepository.recordRejected(rej)
        }
    }

    /**
     * Тишина Fused (tracking-reliability 3.1, причина no-fix): доставок нет дольше
     * порога при выключенной паузе — фиксируем интервал без фиксов, чтобы дыра
     * была объяснена, а не пустой. Вызывается только когда писать нечего.
     */
    private suspend fun flushIfStarved() {
        if (System.currentTimeMillis() - lastFixTime < NO_FIX_GAP_MS) return
        val container = (application as FogMapApp).container
        val paused = runCatching {
            container.dataStore.data.first()[PrefsKeys.PAUSED] ?: false
        }.getOrDefault(false)
        if (paused || !canTrack(this)) return
        // Дроссель: не чаще одного no-fix за порог (сдвигаем метку).
        lastFixTime = System.currentTimeMillis()
        runCatching {
            container.fogRepository.recordRejected(mapOf(FogRepository.REJECT_NO_FIX to 1))
        }
    }

    /**
     * Best-effort сброс при убийстве (tracking-reliability 2.1): потери
     * ограничиваются одним flush-интервалом вместо 30 сек молча.
     */
    private fun flushBlocking() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000L) { flush() }
            }
        }
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
        // Пауза переключается только из Настроек (trust-v2 4.1): внешних
        // action здесь больше нет, intent игнорируется.
        if (!TrackingPreconditions.playServicesAvailable(this) || !canTrack(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Свайп из недавних: система сейчас убьет процесс — сбрасываем пачку.
        flushBlocking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(callback)
        }
        flushBlocking()
        flushJob?.cancel()
        super.onDestroy()
    }

    // --- Статусное уведомление «пишет трек» (trust-v2 4.1: без действий,
    // пауза только из Настроек) ---

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
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "FogMap на паузе" else "FogMap пишет трек")
            .setContentText(
                if (paused) "Продолжите запись в Настройки — Запись" else "Туман открывается"
            )
            .setSmallIcon(R.drawable.ic_stat_fog)
            .setContentIntent(openApp)
            .setOngoing(true)
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
        /** Flush-интервал (tracking-reliability 2.1): потери при убийстве — не больше него. */
        const val FLUSH_INTERVAL_MS = 15_000L
        const val FLUSH_SIZE = 20
        /** Тишина Fused дольше порога = интервал no-fix (tracking-reliability 3.1). */
        const val NO_FIX_GAP_MS = 5 * 60_000L

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
