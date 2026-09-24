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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import ru.fogmap.FogMapApp
import ru.fogmap.MainActivity
import ru.fogmap.R
import ru.fogmap.data.FogRepository
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.RawPoint
import ru.fogmap.data.db.RawFixEntity
import ru.fogmap.diag.DevLog

/**
 * Фоновый трекинг (spec tracking, задачи 3.1–3.3):
 * ForegroundService(type=location) → Fused 5–10 сек / 10–20 м →
 * фильтры → батч-буфер → Room-транзакция → Flow-обновление UI.
 * Пауза — флаг в DataStore, авторестарт — BootReceiver.
 */
class TrackingService : LifecycleService() {

    private val buffer = mutableListOf<RawPoint>()
    /** Сырой черный ящик (track-debug): каждый fix до фильтров, сливается на flush. */
    private val rawBuffer = mutableListOf<RawFixEntity>()
    /** Отбросы текущей пачки: причина -> count (tracking-reliability 3.1). */
    private val rejected = mutableMapOf<String, Long>()
    /** Счётчики веток TrustEngine текущей пачки (fix-walk-fog-verdict 6.1). */
    private val branchCounts = mutableMapOf<String, Long>()
    private var trackId: Long = -1
    private var flushJob: Job? = null
    /**
     * Сериализация flush (fix-import-metrics 1.2): периодический запуск и
     * запуск по переполнению буфера не должны идти одновременно.
     */
    private val flushMutex = Mutex()
    /** Дата текущего суток-чанка (trust-v2 3.1): полночь режет чанк. */
    private var chunkDate: java.time.LocalDate = java.time.LocalDate.now()
    /** Момент последней доставки Fused (любой, даже отброшенной). */
    private var lastFixTime: Long = System.currentTimeMillis()
    /** Кэш флага паузы (gps-trust-filter): вердикт и запись — синхронно в колбэке. */
    @Volatile
    private var pausedCached: Boolean = false
    /** Эко-режим (eco-always-on): всегда включен, флага и тумблеров нет. */
    @Volatile
    private var ecoCached: Boolean = true
    /** Текущий эко-профиль опроса (battery-eco 2.1). */
    @Volatile
    private var ecoProfile: EcoGovernor.Profile = EcoGovernor.Profile.ACTIVE
    /** Серия подряд STAND для входа в STANDBY (battery-eco 2.2). */
    private var standStreak: Int = 0
    /** Якорь STANDBY для дистанционного пробуждения (battery-eco 2.2). */
    @Volatile
    private var standbyAnchorLat: Double? = null
    @Volatile
    private var standbyAnchorLon: Double? = null
    /** Снимок якоря на момент STANDBY->BURST для честного префикса (wake-balance 2.2). */
    @Volatile
    private var wakeAnchorLat: Double? = null
    @Volatile
    private var wakeAnchorLon: Double? = null
    @Volatile
    private var wakeAnchorTime: Long = 0L
    /** Источник последнего пробуждения для eco_state (wake-balance 4.1). */
    @Volatile
    private var lastWakeSource: String = EcoGovernor.WakeSource.GPS
    /** Момент последней достоверной скорости (fog-eco-reliability): держит ACTIVE
     * против пробок. Холостые BURST в метрику идут, но сна-вето больше нет. */
    @Volatile
    private var lastSpeedLatchMs: Long = 0L
    /** Ранние сигналы (wake-balance 1.1/1.2): motion-сенсор и WiFi-колбэк. */
    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private var motionListener: TriggerEventListener? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var wifiConnected: Boolean = false
    /** Дедлайн BURST-окна и последний ресаб (battery-eco 2.3/2.5). */
    private var burstDeadlineMs: Long = 0L
    private var lastResubMs: Long = 0L
    private var lastStandbyEnterMs: Long = 0L
    /** Эко-счетчики текущей пачки (battery-eco 1.3): сливаются во flush. */
    private var ecoFixCount: Long = 0L
    private var ecoStandCount: Long = 0L
    private var ecoRawSample: Long = 0L
    /** Холостые BURST без подтверждения (wake-balance 4.1): сливаются во flush. */
    private var idleBurstPending: Long = 0L
    /** GPS-время профиля с прошлого flush (battery-eco 1.3). */
    private var lastFlushWallMs: Long = System.currentTimeMillis()
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
        // ВРЕМЕННОЕ (dev-logging): старт сервиса.
        DevLog.i("TRACK", "service_start", mapOf("mode" to ecoModeTag()))
        lifecycleScope.launch {
            val container = (application as FogMapApp).container
            // Подписка на паузу: при включенной паузе точки не пишем.
            // Флаг дублируется в pausedCached, чтобы onRawLocation оставался
            // синхронным (вердикт + запись без гонок между колбэками).
            // Эко всегда включен (eco-always-on): тумблеров нет.
            launch {
                container.dataStore.data.collect { prefs ->
                    val paused = prefs[PrefsKeys.PAUSED] ?: false
                    pausedCached = paused
                    updateNotification(paused)
                }
            }
            // День-атом (day-track-history D1/D5): переоткрываем строку даты,
            // а не плодим обломок на каждый старт. Пустой день материализуется
            // строкой с нулями даже до первой движущейся точки.
            trackId = container.trackRepository.openDayChunk()
            chunkDate = java.time.LocalDate.now()
            // Нули веток дня видимы с первого момента (6.2), не после flush.
            runCatching { container.fogRepository.recordBranches(emptyMap(), chunkDate) }
            // Эко-профиль с прошлого запуска (battery-eco 2.4).
            runCatching {
                val saved = container.dataStore.data.first()[PrefsKeys.ECO_PROFILE]
                ecoProfile = EcoGovernor.fromName(saved)
                if (ecoProfile == EcoGovernor.Profile.BURST) {
                    ecoProfile = EcoGovernor.Profile.ACTIVE
                }
            }
            // Якорь STANDBY с прошлого запуска (wake-balance 2.1): первое
            // смещение после рестарта считается от него, а не от нуля.
            runCatching {
                val prefs = container.dataStore.data.first()
                val lat = prefs[PrefsKeys.ANCHOR_LAT]
                val lon = prefs[PrefsKeys.ANCHOR_LON]
                val at = prefs[PrefsKeys.ANCHOR_TIME] ?: 0L
                if (lat != null && lon != null) {
                    standbyAnchorLat = lat
                    standbyAnchorLon = lon
                    wakeAnchorTime = at
                    lastWakeSource = EcoGovernor.WakeSource.RESTART
                }
            }
            lastFlushWallMs = System.currentTimeMillis()
            // Ранние сигналы пробуждения (wake-balance 1.1/1.2): без новых
            // разрешений, деградация к gps-слою при отсутствии железа.
            startMotionWake()
            startWifiWake()
            // ВРЕМЕННОЕ (dev-logging): чанк старта (только id, без координат).
            DevLog.i(
                "TRACK", "day_chunk",
                mapOf("track_id" to trackId, "mode" to ecoModeTag(), "profile" to ecoProfile.name)
            )
            subscribeFused(ecoProfile)
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
        val accOrDef = if (loc.hasAccuracy()) loc.accuracy else -1f
        val speedOrNull = if (loc.hasSpeed()) loc.speed else null
        val input = LocationFilter.Input(
            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
            speed = speedOrNull,
            isMock = isMock
        )
        val kind = MotionKindClassifier.classify(speedOrNull, trustPrev?.kind)
        val reason = LocationFilter.reason(input, kind)
        // Жёсткие отбросы (mock/no-acc/speed>150): без истории trust,
        // без track_points (3.3). BAD_ACCURACY kind-порога — мягкий путь ниже.
        val hardReject = reason == LocationFilter.Reason.MOCK ||
            reason == LocationFilter.Reason.NO_ACCURACY ||
            reason == LocationFilter.Reason.BAD_SPEED ||
            (reason == LocationFilter.Reason.BAD_ACCURACY && !(loc.hasAccuracy() && loc.accuracy > 0f))
        if (hardReject) {
            var wakeTarget: EcoTarget? = null
            synchronized(buffer) {
                rejected[reason.key] = (rejected[reason.key] ?: 0) + 1
                ecoFixCount += 1
                // Черный ящик: отброс фильтра сохраняется с координатами.
                rawBuffer.add(
                    rawOf(
                        loc = loc, accOrDef = accOrDef, speedOrNull = speedOrNull,
                        isMock = isMock, filter = reason.key,
                        state = null, trust = null, openFog = null, rejectReason = null,
                        history = trustHistory.toList()
                    )
                )
                if (ecoProfile == EcoGovernor.Profile.STANDBY &&
                    reason == LocationFilter.Reason.NO_ACCURACY
                ) {
                    val anchorLat = standbyAnchorLat
                    val anchorLon = standbyAnchorLon
                    if (anchorLat != null && anchorLon != null) {
                        val distM = FogRepository.haversineM(
                            anchorLat, anchorLon, loc.latitude, loc.longitude
                        ).toLong()
                        if (distM >= EcoGovernor.WAKE_DISTANCE_M.toLong()) {
                            burstDeadlineMs = System.currentTimeMillis() + EcoGovernor.BURST_WINDOW_MS
                            wakeTarget = EcoTarget(
                                EcoGovernor.Profile.BURST, distM, "WAKE_ACC",
                                EcoGovernor.WakeSource.GPS
                            )
                        }
                    }
                }
            }
            if (wakeTarget != null) {
                val t = wakeTarget
                lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict, source = t.source) }
            }
            return
        }
        // Доверие по последовательности (gps-trust-filter): вердикт считается
        // синхронно в колбэке; история в памяти — все принятые точки (включая
        // дропнутую STAND-статику, иначе холодный старт не выйдет из STAND).
        val hp = TrustEngine.HistPoint(
            time = loc.time, lat = loc.latitude, lon = loc.longitude, acc = loc.accuracy,
            speed = speedOrNull
        )
        synchronized(buffer) {
            if (pausedCached) {
                rejected[FogRepository.REJECT_PAUSED] =
                    (rejected[FogRepository.REJECT_PAUSED] ?: 0) + 1
                ecoFixCount += 1
                rawBuffer.add(
                    rawOf(
                        loc = loc, accOrDef = accOrDef, speedOrNull = speedOrNull,
                        isMock = isMock, filter = FogRepository.REJECT_PAUSED,
                        state = null, trust = null, openFog = null, rejectReason = null,
                        history = trustHistory.toList()
                    )
                )
                return
            }
            // Мягкий путь (3.2): acc хуже порога kind → raw + история trust
            // с низким весом (ACC_TRUST_CAP), НЕ в track_points / не открывает.
            if (reason == LocationFilter.Reason.BAD_ACCURACY) {
                val historySnap = trustHistory.toList()
                val prevSnap = trustPrev
                val verdict = TrustEngine.evaluate(trustPrev, historySnap, hp)
                trustPrev = verdict.next
                rejected[reason.key] = (rejected[reason.key] ?: 0) + 1
                ecoFixCount += 1
                countBranches(verdict)
                if (verdict.resetHistory) {
                    val keep = trustHistory.lastOrNull()
                    trustHistory.clear()
                    if (keep != null) trustHistory.addLast(keep)
                }
                trustHistory.addLast(hp)
                TrustEngine.pruneHistory(trustHistory, hp.time)
                rawBuffer.add(
                    rawOf(
                        loc = loc, accOrDef = accOrDef, speedOrNull = speedOrNull,
                        isMock = isMock, filter = reason.key,
                        state = verdict.state.name, trust = verdict.trust,
                        openFog = 0, rejectReason = verdict.countReject,
                        history = historySnap, prev = prevSnap
                    )
                )
                if (ecoProfile == EcoGovernor.Profile.STANDBY) {
                    val anchorLat = standbyAnchorLat
                    val anchorLon = standbyAnchorLon
                    if (anchorLat != null && anchorLon != null) {
                        val distM = FogRepository.haversineM(
                            anchorLat, anchorLon, loc.latitude, loc.longitude
                        ).toLong()
                        if (distM >= EcoGovernor.WAKE_DISTANCE_M.toLong()) {
                            burstDeadlineMs = System.currentTimeMillis() + EcoGovernor.BURST_WINDOW_MS
                            val t = EcoTarget(
                                EcoGovernor.Profile.BURST, distM, "WAKE_ACC",
                                EcoGovernor.WakeSource.GPS
                            )
                            lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict, source = t.source) }
                        }
                    }
                }
                return
            }
            val historySnap = trustHistory.toList()
            val prevSnap = trustPrev
            val verdict = TrustEngine.evaluate(trustPrev, historySnap, hp)
            trustPrev = verdict.next
            verdict.countReject?.let { key ->
                rejected[key] = (rejected[key] ?: 0) + 1
            }
            ecoFixCount += 1
            if (verdict.state == TrustEngine.State.STAND) ecoStandCount += 1
            countBranches(verdict)
            // Сброс окна после существенного трогания (track-fix 19.09):
            // стояночные нули выкидываются, но последний кадр остается
            // якорем непрерывности (иначе медиане не из чего считаться).
            if (verdict.resetHistory) {
                val keep = trustHistory.lastOrNull()
                trustHistory.clear()
                if (keep != null) trustHistory.addLast(keep)
            }
            trustHistory.addLast(hp)
            // История ограничена временем (fix-eco-signal-loss 1.1), а не
            // восемью точками: на 1 Гц окно из 8 точек покрывало 7 секунд
            // и движение классифицировалось как статика.
            TrustEngine.pruneHistory(trustHistory, hp.time)
            // Черный ящик: вердикт пишется всегда, включая STAND.
            // В STANDBY семплируем сыряк 1/6 (battery-eco 2.5), иначе раздуваем БД.
            val wantRaw = !ecoCached || ecoProfile != EcoGovernor.Profile.STANDBY ||
                (ecoRawSample++ % 6L == 0L)
            if (wantRaw) {
                rawBuffer.add(
                    rawOf(
                        loc = loc, accOrDef = accOrDef, speedOrNull = speedOrNull,
                        isMock = isMock, filter = LocationFilter.Reason.OK.key,
                        state = verdict.state.name, trust = verdict.trust,
                        openFog = if (verdict.openFog) 1 else 0,
                        rejectReason = verdict.countReject,
                        history = historySnap, prev = prevSnap
                    )
                )
            }
            // Эко-губернатор (battery-eco 2.2/2.3): решение о профиле — после
            // вердикта, сам переход — вне synchronized (там переподписка).
            val target = ecoTargetAfterVerdict(verdict, loc)
            // День-атом (day-track-history D2 + fix-walk-fog-verdict 4.1):
            // STAND в STANDBY не пишется (6 часов дома = 0 точек); в ACTIVE
            // и BURST строка есть с openFog=false (дистанция не растёт).
            if (verdict.state == TrustEngine.State.STAND) {
                if (ecoProfile != EcoGovernor.Profile.STANDBY) {
                    buffer.add(
                        RawPoint(
                            time = loc.time, lat = loc.latitude, lon = loc.longitude,
                            acc = loc.accuracy, speed = speedOrNull,
                            trust = verdict.trust, openFog = false,
                            state = verdict.state.name, rejectReason = verdict.countReject
                        )
                    )
                }
                if (target != null && target.profile != ecoProfile) {
                    val t = target
                    lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict, source = t.source) }
                }
                if (buffer.size >= FLUSH_SIZE) lifecycleScope.launch { flush() }
                return
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
            if (target != null && target.profile != ecoProfile) {
                val t = target
                lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict, source = t.source) }
            }
            if (buffer.size >= FLUSH_SIZE) lifecycleScope.launch { flush() }
        }
    }

    /** Инкременты веток вердикта + kind (fix-walk-fog-verdict 6.1). */
    private fun countBranches(verdict: TrustEngine.Verdict) {
        for (b in verdict.branches) branchCounts[b] = (branchCounts[b] ?: 0L) + 1L
        val kindKey = "kind_${verdict.kind.name}"
        branchCounts[kindKey] = (branchCounts[kindKey] ?: 0L) + 1L
    }

    /**
     * Строка черного ящика (track-debug 1.2): собирается синхронно в колбэке
     * из того же fix и того же вердикта что и точка трека. Чистая математика —
     * в [RawTrace], здесь только разбор Location.
     */
    private fun rawOf(
        loc: Location,
        accOrDef: Float,
        speedOrNull: Float?,
        isMock: Boolean,
        filter: String,
        state: String?,
        trust: Int?,
        openFog: Int?,
        rejectReason: String?,
        history: List<TrustEngine.HistPoint>,
        prev: TrustEngine.PrevState? = null
    ): RawFixEntity = RawTrace.build(
        time = loc.time, lat = loc.latitude, lon = loc.longitude,
        acc = accOrDef, speed = speedOrNull, isMock = isMock, filter = filter,
        state = state, trust = trust, openFog = openFog, rejectReason = rejectReason,
        history = history, prev = prev
    )

    private suspend fun flush() {
        // Сериализация (fix-import-metrics 1.2): таймер и переполнение буфера
        // не пересекаются, дистанция/время/клетки не двоятся.
        flushMutex.withLock { flushLocked() }
    }

    /** Тело flush под [flushMutex]: чтение буферов, транзакция, метрики, лог. */
    private suspend fun flushLocked() {
        val container = (application as FogMapApp).container
        // Граница суток — ДО ветки starved (day-track-history D5/D6): полночь
        // режет день строго в 00:00, новый день материализуется пустой строкой
        // даже если писать нечего; коридор через полночь не тянется.
        runCatching {
            val today = java.time.LocalDate.now()
            if (today != chunkDate) {
                trackId = container.trackRepository.openDayChunk(today)
                chunkDate = today
                runCatching { container.fogRepository.recordBranches(emptyMap(), today) }
            } else if (container.db.trackDao().trackById(trackId) == null) {
                // Строка дня исчезла (fix-stale-track-id 24.09): импорт/rebuild
                // track-debug или удаление трека пересоздали её с новым id, а
                // сервис кэширует trackId в поле и без перезапуска не перечитывает —
                // точки после импорта ушли бы в сироты (вечерние маршруты 24.09
                // пропали из истории при живом raw). Переоткрываем день.
                trackId = container.trackRepository.openDayChunk(today)
                DevLog.w(
                    "TRACK", "day_chunk_recovered",
                    mapOf("track_id" to trackId, "cause" to "row_missing")
                )
            }
        }
        // BURST-таймаут без новых фиксов (battery-eco 2.3): тихий возврат в сон.
        if (ecoCached && ecoProfile == EcoGovernor.Profile.BURST &&
            System.currentTimeMillis() > burstDeadlineMs && burstDeadlineMs > 0
        ) {
            enterProfile(
                EcoGovernor.Profile.STANDBY, verdict = "TIMEOUT",
                source = EcoGovernor.WakeSource.TIMEOUT
            )
        }
        // GPS-время профиля с прошлого flush (battery-eco 1.3): весь интервал
        // сервис держал текущий профиль подписки.
        val nowWall = System.currentTimeMillis()
        val gpsMs = (nowWall - lastFlushWallMs).coerceIn(0L, FLUSH_INTERVAL_MS * 2)
        lastFlushWallMs = nowWall
        // suspend-вызовы — строго вне synchronized (иначе critical section).
        val ecoSnapshot: Map<String, Long> = synchronized(buffer) {
            val m = HashMap<String, Long>()
            if (ecoFixCount > 0) m[FogRepository.ECO_FIX] = ecoFixCount
            if (ecoStandCount > 0) m[FogRepository.ECO_STAND] = ecoStandCount
            if (idleBurstPending > 0) m[FogRepository.ECO_IDLE_BURST] = idleBurstPending
            // GPS-время пишем всегда, даже в STAND без точек — иначе
            // 6 часов дома дадут 0 во всех метриках и A/B несравним.
            m[FogRepository.ECO_GPS_MS] = gpsMs
            ecoFixCount = 0
            ecoStandCount = 0
            idleBurstPending = 0
            m
        }
        val branchSnapshot: Map<String, Long> = synchronized(buffer) {
            val copy = branchCounts.toMap()
            branchCounts.clear()
            copy
        }
        // Тишина Fused (tracking-reliability 3.1): ecoSnapshot — не признак
        // данных (ECO_GPS_MS кладется всегда), иначе ветка no-fix была
        // недостижима и провалы доставки 5-12 минут оставались невидимыми
        // (fix-eco-signal-loss 3.1).
        val starved: Boolean = synchronized(buffer) {
            buffer.isEmpty() && rejected.isEmpty() && rawBuffer.isEmpty()
        }
        // Эко/ветки сливаем даже в starved (нули веток видимы всегда, 6.2).
        if (starved) {
            runCatching { container.fogRepository.recordEco(ecoSnapshot) }
            runCatching { container.fogRepository.recordBranches(branchSnapshot, chunkDate) }
            DevLog.i(
                "TRACK", "day_branches",
                EcoLogPayload.branchPayload(branchSnapshot)
            )
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
        val rawBatch: List<RawFixEntity> = synchronized(buffer) {
            val copy = rawBuffer.toList()
            rawBuffer.clear()
            copy
        }
        // ВРЕМЕННОЕ (dev-logging): замер транзакции flush (только counts, без координат).
        val t0 = System.nanoTime()
        var newCells = 0
        val batchSize = batch.size
        val rejSize = rej.values.sum()
        var prefixCm = 0L
        var prefixN = 0L
        var gapCm = 0L
        var gapN = 0L
        var prefixM: Long? = null
        var prefixSrc: String? = null
        runCatching {
            // День уже гарантирован проверкой в начале flush (см. выше):
            // батч всегда пишется в строку текущей даты, без хвоста прошлого дня.
            if (rawBatch.isNotEmpty()) {
                container.db.rawFixDao().insertAll(rawBatch)
            }
            if (batch.isNotEmpty()) {
                // Якорь пробуждения для честного префикса/коридора (wake-balance
                // 2.2): потребляется первым непустым flush после выхода, чтобы
                // дистанция не считалась дважды на следующих батчах.
                // prev читаем ДО вставки (иначе хвост — свой же батч).
                val prevBefore = container.db.trackDao().lastPoint(trackId)
                val aLat = wakeAnchorLat
                val aLon = wakeAnchorLon
                val aTime = wakeAnchorTime
                val wakeAnchorPoint = if (aLat != null && aLon != null) {
                    RawPoint(
                        time = aTime, lat = aLat, lon = aLon,
                        acc = 10f, speed = null, trust = 80,
                        openFog = true, state = "STAND", rejectReason = null
                    )
                } else null
                val appended = container.fogRepository
                    .appendPoints(trackId, batch, wakeAnchor = wakeAnchorPoint)
                newCells = appended.newCells
                // Страховка той же защиты внутри appendPoints (гонка с rebuild):
                // подтягиваем фактический id, чтобы next flush читал хвост дня.
                if (appended.trackId != trackId) {
                    trackId = appended.trackId
                    DevLog.w(
                        "TRACK", "day_chunk_recovered",
                        mapOf("track_id" to trackId, "cause" to "stale_id")
                    )
                }
                // Префикс спрямления (battery-eco 3): якорь STANDBY -> first.
                // В бюджет префикса идет ТОЛЬКО пробуждение от якоря; разрыв
                // prev->first после тишины — отдельная метрика (fix-eco-signal-loss 4.1).
                val f = batch.first()
                if (wakeAnchorPoint != null) {
                    val d = FogRepository.haversineM(aLat!!, aLon!!, f.lat, f.lon)
                    val kind = EcoLogPayload.prefixKind(hasAnchor = true, distanceM = d, gapS = 0)
                    if (kind != null) {
                        prefixCm = (d * 100).toLong()
                        prefixN = 1L
                        prefixM = d.toLong()
                        prefixSrc = kind
                    }
                    wakeAnchorLat = null
                    wakeAnchorLon = null
                } else if (prevBefore != null) {
                    val d = FogRepository.haversineM(prevBefore.lat, prevBefore.lon, f.lat, f.lon)
                    // Пишем только значимые разрывы после тишины/STANDBY,
                    // межбатчевые 8-секундные шаги не шумят.
                    val dtS = (f.time - prevBefore.time) / 1000
                    val kind = EcoLogPayload.prefixKind(hasAnchor = false, distanceM = d, gapS = dtS)
                    if (kind != null) {
                        gapCm = (d * 100).toLong()
                        gapN = 1L
                        prefixM = d.toLong()
                        prefixSrc = kind
                    }
                }
            }
            if (rej.isNotEmpty()) container.fogRepository.recordRejected(rej)
            container.fogRepository.recordBranches(branchSnapshot, chunkDate)
            val ecoToWrite = HashMap<String, Long>(ecoSnapshot)
            // Пробуждение БД (battery-eco 1.3): flush с реальной записью.
            ecoToWrite[FogRepository.ECO_FLUSH] = 1L
            if (prefixN > 0) {
                ecoToWrite[FogRepository.ECO_PREFIX_CM] = prefixCm
                ecoToWrite[FogRepository.ECO_PREFIX_N] = prefixN
                addPrefixBucket(ecoToWrite, prefixCm)
            }
            if (gapN > 0) {
                ecoToWrite[FogRepository.ECO_GAP_CM] = gapCm
                ecoToWrite[FogRepository.ECO_GAP_N] = gapN
            }
            container.fogRepository.recordEco(ecoToWrite)
        }
        val txnMs = (System.nanoTime() - t0) / 1e6
        val mode = ecoModeTag()
        val txnStr = String.format(Locale.US, "%.1f", txnMs)
        // Эко-снимок пачки в лог (logging-gap 1.1): те же числа что в счетчики —
        // один JSONL самодостаточен для сверки без доступа к БД.
        val payloadFix = ecoSnapshot[FogRepository.ECO_FIX] ?: 0L
        val payloadStand = ecoSnapshot[FogRepository.ECO_STAND] ?: 0L
        val payloadGpsMs = ecoSnapshot[FogRepository.ECO_GPS_MS] ?: 0L
        if (txnMs > 500.0) {
            DevLog.w(
                "TRACK", "slow_flush",
                EcoLogPayload.flushPayload(
                    batch = batchSize, rejected = rejSize,
                    txnMs = txnStr, newCells = newCells,
                    mode = mode, profile = ecoProfile.name,
                    ecoFix = payloadFix, ecoStand = payloadStand, ecoGpsMs = payloadGpsMs,
                    prefixM = prefixM, prefixSrc = prefixSrc
                )
            )
        } else {
            DevLog.i(
                "TRACK", "flush",
                EcoLogPayload.flushPayload(
                    batch = batchSize, rejected = rejSize,
                    txnMs = txnStr, newCells = newCells,
                    mode = mode, profile = ecoProfile.name,
                    ecoFix = payloadFix, ecoStand = payloadStand, ecoGpsMs = payloadGpsMs,
                    prefixM = prefixM, prefixSrc = prefixSrc
                )
            )
        }
        if (branchSnapshot.isNotEmpty()) {
            DevLog.i(
                "TRACK", "day_branches",
                EcoLogPayload.branchPayload(branchSnapshot)
            )
        }
    }

    /**
     * Бакет распределения префикса (fix-eco-signal-loss 4.3): медиана
     * считается по гистограмме, а не как среднее, без миграции БД.
     */
    private fun addPrefixBucket(eco: MutableMap<String, Long>, cm: Long) {
        val m = cm / 100.0
        val key = when {
            m < 100.0 -> FogRepository.ECO_PREFIX_B100_N
            m < 200.0 -> FogRepository.ECO_PREFIX_B200_N
            m < 500.0 -> FogRepository.ECO_PREFIX_B500_N
            else -> FogRepository.ECO_PREFIX_BHI_N
        }
        eco[key] = 1L
    }

    /**
     * Тишина Fused (tracking-reliability 3.1, причина no-fix): доставок нет дольше
     * порога при выключенной паузе — фиксируем интервал без фиксов, чтобы дыра
     * была объяснена, а не пустой. Вызывается только когда писать нечего.
     */
    private suspend fun flushIfStarved() {
        val gapMs = System.currentTimeMillis() - lastFixTime
        if (gapMs < NO_FIX_GAP_MS) return
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
        // ВРЕМЕННОЕ (dev-logging): тишина Fused — фактическая длительность,
        // а не порог срабатывания (fix-eco-signal-loss 3.1/dev-logging).
        DevLog.i(
            "TRACK", "no_fix",
            EcoLogPayload.noFixPayload(gapMs, ecoModeTag(), ecoProfile.name)
        )
    }

    /**
     * Best-effort сброс при убийстве (tracking-reliability 2.1): потери
     * ограничиваются одним flush-интервалом вместо 30 сек молча.
     */
    private fun flushBlocking() {
        // Мьютекс занят — держатель ждёт главный поток (lifecycleScope),
        // блокировать его самим нельзя: путь best-effort (fix-import-metrics 1.2).
        if (flushMutex.isLocked) return
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000L) { flush() }
            }
        }
    }

    private fun subscribeFused(profile: EcoGovernor.Profile = EcoGovernor.Profile.ACTIVE) {
        val params = EcoGovernor.paramsFor(profile)
        val request = LocationRequest.Builder(params.priority, params.intervalMs)
            .setMinUpdateIntervalMillis(params.minIntervalMs)
            .setMinUpdateDistanceMeters(params.distanceM)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    /** Переподписка на профиль (battery-eco 2.1/2.5): единая точка, шторм гасится. */
    private fun resubscribe(profile: EcoGovernor.Profile) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(callback)
        }
        subscribeFused(profile)
        lastResubMs = System.currentTimeMillis()
    }

    /**
     * Ранние сигналы пробуждения (wake-balance 1.1/1.2): Significant Motion
     * и разрыв WiFi переводят только STANDBY->BURST. В ACTIVE переводит лишь
     * подтверждение движения TrustEngine. Без железа — тихая деградация
     * к gps-слою, сервис не падает.
     */
    private fun requestBurstFromSensor(source: String) {
        if (!ecoCached || ecoProfile != EcoGovernor.Profile.STANDBY) return
        val anchorLat = standbyAnchorLat
        val anchorLon = standbyAnchorLon
        val wakeM = if (anchorLat != null && anchorLon != null) {
            // Дистанция до якоря без свежих координат неизвестна — для лога
            // честно пишем NO_ANCHOR, сам якорь снимется в enterProfile.
            EcoLogPayload.NO_ANCHOR_M
        } else EcoLogPayload.NO_ANCHOR_M
        burstDeadlineMs = System.currentTimeMillis() + EcoGovernor.BURST_WINDOW_MS
        lifecycleScope.launch {
            enterProfile(
                EcoGovernor.Profile.BURST, wakeM = wakeM,
                verdict = "WAKE", source = source
            )
        }
    }

    private fun startMotionWake() {
        runCatching {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            sensorManager = sm
            motionSensor = sensor
            val listener = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    // One-shot сенсор: перевооружаемся сразу, решение — в BURST.
                    runCatching {
                        sm.requestTriggerSensor(this, sensor)
                    }
                    requestBurstFromSensor(EcoGovernor.WakeSource.MOTION)
                }
            }
            motionListener = listener
            sm.requestTriggerSensor(listener, sensor)
        }
    }

    private fun stopMotionWake() {
        runCatching {
            val sm = sensorManager
            val l = motionListener
            val s = motionSensor
            if (sm != null && l != null && s != null) sm.cancelTriggerSensor(l, s)
        }
        sensorManager = null
        motionSensor = null
        motionListener = null
    }

    private fun startWifiWake() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            wifiConnected = isWifiConnected(cm)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifiConnected = isWifiConnected(cm)
                }
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities
                ) {
                    wifiConnected = isWifiConnected(cm)
                }
                override fun onLost(network: Network) {
                    val wasWifi = wifiConnected
                    wifiConnected = isWifiConnected(cm)
                    // Разрыв домашнего WiFi — вспомогательный триггер BURST.
                    // Без WiFi вообще (мобильные данные) молчим: страхуют
                    // motion и gps-смещение.
                    if (wasWifi && !wifiConnected) {
                        requestBurstFromSensor(EcoGovernor.WakeSource.WIFI)
                    }
                }
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        }
    }

    private fun stopWifiWake() {
        runCatching {
            val cm = connectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb)
        }
        connectivityManager = null
        networkCallback = null
    }

    private fun isWifiConnected(cm: ConnectivityManager): Boolean {
        return runCatching {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }

    private fun ecoModeTag(): String = if (ecoCached) "eco" else "base"

    /**
     * Решение губернатора после вердикта (battery-eco 2.2/2.3, вызывается
     * внутри synchronized): возвращает цель с причиной (дистанция до якоря
     * целым числом метров, вердикт) или null (без смены). Причина нужна
     * для eco_state — один JSONL самодостаточен для сверки (logging-gap).
     * Base всегда ACTIVE. Переподписка — снаружи, в lifecycleScope.
     */
    private data class EcoTarget(
        val profile: EcoGovernor.Profile,
        val wakeM: Long,
        val verdict: String,
        val source: String = EcoGovernor.WakeSource.GPS
    )

    private fun ecoTargetAfterVerdict(
        verdict: TrustEngine.Verdict,
        loc: Location
    ): EcoTarget? {
        if (!ecoCached) {
            return if (ecoProfile != EcoGovernor.Profile.ACTIVE) {
                EcoTarget(EcoGovernor.Profile.ACTIVE, EcoLogPayload.NO_ANCHOR_M, "MODE")
            } else null
        }
        val now = System.currentTimeMillis()
        // Speed-latch (fog-eco-reliability): достоверная скорость держит ACTIVE
        // против пробок во всех профилях; вердикт точки при этом не меняется.
        if (loc.hasSpeed() && loc.hasAccuracy() &&
            EcoGovernor.isSpeedLatch(loc.speed, loc.accuracy)
        ) {
            lastSpeedLatchMs = now
        }
        return when (ecoProfile) {
            EcoGovernor.Profile.ACTIVE -> {
                if (verdict.state == TrustEngine.State.STAND) {
                    standStreak += 1
                    if (EcoGovernor.activeMayStandby(
                            standStreak, now, lastStandbyEnterMs, lastSpeedLatchMs
                        )
                    ) {
                        standbyAnchorLat = loc.latitude
                        standbyAnchorLon = loc.longitude
                        standStreak = 0
                        EcoTarget(EcoGovernor.Profile.STANDBY, 0L, verdict.state.name)
                    } else null
                } else {
                    standStreak = 0
                    null
                }
            }
            EcoGovernor.Profile.STANDBY -> {
                val anchorLat = standbyAnchorLat
                val anchorLon = standbyAnchorLon
                if (anchorLat == null || anchorLon == null) {
                    standbyAnchorLat = loc.latitude
                    standbyAnchorLon = loc.longitude
                    null
                } else {
                    val distM = FogRepository.haversineM(
                        anchorLat, anchorLon, loc.latitude, loc.longitude
                    ).toLong()
                    // GPS будит всегда, motion не требуется (fog-eco-reliability):
                    // якорь не двигаем каждый фикс, иначе окно уедет вместе
                    // с медленным пешеходом и никогда не сработает (тот же
                    // принцип что и STATIC_RADIUS в TrustEngine).
                    val target = EcoGovernor.standbyTarget(verdict.state, distM)
                    if (target != null) {
                        burstDeadlineMs = now + EcoGovernor.BURST_WINDOW_MS
                        EcoTarget(target, distM, verdict.state.name)
                    } else null
                }
            }
            EcoGovernor.Profile.BURST -> {
                val speedMps: Float? = if (loc.hasSpeed()) loc.speed else null
                val accM: Float? = if (loc.hasAccuracy()) loc.accuracy else null
                val target = EcoGovernor.burstTarget(verdict.state, speedMps, accM)
                if (target != null) {
                    // Префикс фиксируется во flush по коридору якорь->first,
                    // здесь только переход (батарейка: без лишнего haversine).
                    val anchorLat = standbyAnchorLat
                    val anchorLon = standbyAnchorLon
                    val distM = if (anchorLat != null && anchorLon != null) {
                        FogRepository.haversineM(
                            anchorLat, anchorLon, loc.latitude, loc.longitude
                        ).toLong()
                    } else EcoLogPayload.NO_ANCHOR_M
                    // Вердикт точки не меняется; в eco_state честно пишем,
                    // чем вышли: MOVING или SPEED (скорость при грязной истории).
                    val by = if (verdict.state == TrustEngine.State.MOVING) {
                        verdict.state.name
                    } else "SPEED"
                    EcoTarget(target, distM, by, lastWakeSource)
                } else if (now > burstDeadlineMs && burstDeadlineMs > 0) {
                    standbyAnchorLat = loc.latitude
                    standbyAnchorLon = loc.longitude
                    EcoTarget(
                        EcoGovernor.Profile.STANDBY, EcoLogPayload.NO_ANCHOR_M,
                        verdict.state.name, EcoGovernor.WakeSource.TIMEOUT
                    )
                } else null
            }
        }
    }

    /** Вход в профиль: ресаб + персист + лог (battery-eco 2.4, вне critical section). */
    private fun enterProfile(
        profile: EcoGovernor.Profile,
        force: Boolean = false,
        wakeM: Long = EcoLogPayload.NO_ANCHOR_M,
        verdict: String = "?",
        source: String = EcoGovernor.WakeSource.GPS
    ) {
        if (!force) {
            if (profile == ecoProfile) return
            // Дебаунс шторма (battery-eco 2.5): чаще 10 сек не переподписываемся,
            // кроме пробуждения STANDBY->BURST — оно обязано быть мгновенным.
            val wake = ecoProfile == EcoGovernor.Profile.STANDBY && profile == EcoGovernor.Profile.BURST
            if (!wake && System.currentTimeMillis() - lastResubMs < 10_000L) return
        }
        val from = ecoProfile
        ecoProfile = profile
        // Снимок якоря для честного префикса (wake-balance 2.2): первое
        // смещение после выхода считается от якоря сна, а не от хвоста дня.
        if (from == EcoGovernor.Profile.STANDBY && profile == EcoGovernor.Profile.BURST) {
            wakeAnchorLat = standbyAnchorLat
            wakeAnchorLon = standbyAnchorLon
            wakeAnchorTime = System.currentTimeMillis()
            lastWakeSource = source
        }
        if (profile == EcoGovernor.Profile.STANDBY) {
            lastStandbyEnterMs = System.currentTimeMillis()
            // Холостой BURST (fog-eco-reliability): джиттер без движения —
            // якорь уже обновлен вызывателем, считаем только в метрику.
            // Сна-вето больше нет: следующий GPS-сдвиг разбудит как обычно.
            // Признак — source TIMEOUT (verdict тут имя состояния, не причина).
            if (from == EcoGovernor.Profile.BURST && source == EcoGovernor.WakeSource.TIMEOUT) {
                synchronized(buffer) { idleBurstPending += 1 }
            }
            // Персист якоря (wake-balance 2.1): переживает убийство/ребут.
            val lat = standbyAnchorLat
            val lon = standbyAnchorLon
            if (lat != null && lon != null) {
                lifecycleScope.launch {
                    runCatching {
                        (application as? FogMapApp)?.container?.settingsRepository
                            ?.setStandbyAnchor(lat, lon, System.currentTimeMillis())
                    }
                }
            }
        }
        if (profile == EcoGovernor.Profile.ACTIVE) standStreak = 0
        resubscribe(profile)
        val container = (application as? FogMapApp)?.container ?: return
        lifecycleScope.launch {
            runCatching {
                container.settingsRepository.setEcoProfile(EcoGovernor.nameOf(profile))
            }
        }
        DevLog.i(
            "TRACK", "eco_state",
            EcoLogPayload.ecoStatePayload(
                mode = ecoModeTag(),
                profile = profile.name,
                fromProfile = from.name,
                wakeM = wakeM,
                verdict = verdict,
                source = source
            )
        )
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
        // ВРЕМЕННОЕ (dev-logging): остановка сервиса.
        DevLog.i("TRACK", "service_stop", mapOf("mode" to ecoModeTag(), "profile" to ecoProfile.name))
        stopMotionWake()
        stopWifiWake()
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
