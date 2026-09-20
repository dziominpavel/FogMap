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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private var trackId: Long = -1
    private var flushJob: Job? = null
    /** Дата текущего суток-чанка (trust-v2 3.1): полночь режет чанк. */
    private var chunkDate: java.time.LocalDate = java.time.LocalDate.now()
    /** Момент последней доставки Fused (любой, даже отброшенной). */
    private var lastFixTime: Long = System.currentTimeMillis()
    /** Кэш флага паузы (gps-trust-filter): вердикт и запись — синхронно в колбэке. */
    @Volatile
    private var pausedCached: Boolean = false
    /** Эко-режим (battery-eco 1.1): false = base (поведение как раньше). */
    @Volatile
    private var ecoCached: Boolean = false
    /** Текущий эко-профиль опроса (battery-eco 2.1). */
    @Volatile
    private var ecoProfile: EcoGovernor.Profile = EcoGovernor.Profile.ACTIVE
    /** Серия подряд STAND для входа в STANDBY (battery-eco 2.2). */
    private var standStreak: Int = 0
    /** Якорь STANDBY для дистанционного пробуждения (battery-eco 2.2). */
    private var standbyAnchorLat: Double? = null
    private var standbyAnchorLon: Double? = null
    /** Дедлайн BURST-окна и последний ресаб (battery-eco 2.3/2.5). */
    private var burstDeadlineMs: Long = 0L
    private var lastResubMs: Long = 0L
    private var lastStandbyEnterMs: Long = 0L
    /** Эко-счетчики текущей пачки (battery-eco 1.3): сливаются во flush. */
    private var ecoFixCount: Long = 0L
    private var ecoStandCount: Long = 0L
    private var ecoRawSample: Long = 0L
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
            // Эко-флаг — рядом: base ведет себя как раньше, eco включает
            // губернатор STANDBY/BURST (battery-eco 1.1/2.4).
            launch {
                container.dataStore.data.collect { prefs ->
                    val paused = prefs[PrefsKeys.PAUSED] ?: false
                    pausedCached = paused
                    val eco = prefs[PrefsKeys.ECO_MODE] ?: false
                    if (eco != ecoCached) {
                        ecoCached = eco
                        DevLog.i(
                            "TRACK", "mode_changed",
                            mapOf("mode" to ecoModeTag(), "profile" to ecoProfile.name)
                        )
                        if (!eco) enterProfile(EcoGovernor.Profile.ACTIVE, force = true)
                    }
                    updateNotification(paused)
                }
            }
            // День-атом (day-track-history D1/D5): переоткрываем строку даты,
            // а не плодим обломок на каждый старт. Пустой день материализуется
            // строкой с нулями даже до первой движущейся точки.
            trackId = container.trackRepository.openDayChunk()
            chunkDate = java.time.LocalDate.now()
            // Эко-профиль с прошлого запуска (battery-eco 2.4): base всегда ACTIVE.
            runCatching {
                val saved = container.dataStore.data.first()[PrefsKeys.ECO_PROFILE]
                val ecoNow = container.dataStore.data.first()[PrefsKeys.ECO_MODE] ?: false
                ecoCached = ecoNow
                ecoProfile = if (!ecoNow) EcoGovernor.Profile.ACTIVE
                else EcoGovernor.fromName(saved)
                if (ecoProfile == EcoGovernor.Profile.BURST) {
                    ecoProfile = EcoGovernor.Profile.ACTIVE
                }
            }
            lastFlushWallMs = System.currentTimeMillis()
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
        val reason = LocationFilter.reason(input)
        if (reason != LocationFilter.Reason.OK) {
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
            }
            return
        }
        // Доверие по последовательности (gps-trust-filter): вердикт считается
        // синхронно в колбэке; история в памяти — все принятые точки (включая
        // дропнутую STAND-статику, иначе холодный старт не выйдет из STAND).
        val hp = TrustEngine.HistPoint(
            time = loc.time, lat = loc.latitude, lon = loc.longitude, acc = loc.accuracy
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
            val historySnap = trustHistory.toList()
            val prevSnap = trustPrev
            val verdict = TrustEngine.evaluate(trustPrev, historySnap, hp)
            trustPrev = verdict.next
            verdict.countReject?.let { key ->
                rejected[key] = (rejected[key] ?: 0) + 1
            }
            ecoFixCount += 1
            if (verdict.state == TrustEngine.State.STAND) ecoStandCount += 1
            // Сброс окна после существенного трогания (track-fix 19.09):
            // стояночные нули выкидываются, но последний кадр остается
            // якорем непрерывности (иначе медиане не из чего считаться).
            if (verdict.resetHistory) {
                val keep = trustHistory.lastOrNull()
                trustHistory.clear()
                if (keep != null) trustHistory.addLast(keep)
            }
            trustHistory.addLast(hp)
            while (trustHistory.size > TrustEngine.HISTORY_MAX) trustHistory.removeFirst()
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
            // День-атом (day-track-history D2): статика (STAND — стою, открывать
            // нечего) в БД не пишется вообще: ни точки, ни rejected. Движок
            // доверия при этом шагает как раньше (история в памяти нужна,
            // иначе холодный старт никогда не выйдет из STAND); прыжки
            // (SUSPECT/jump) и движение пишутся как раньше.
            if (verdict.state == TrustEngine.State.STAND) {
                if (target != null && target.profile != ecoProfile) {
                    val t = target
                    lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict) }
                }
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
                lifecycleScope.launch { enterProfile(t.profile, wakeM = t.wakeM, verdict = t.verdict) }
            }
            if (buffer.size >= FLUSH_SIZE) lifecycleScope.launch { flush() }
        }
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
        val container = (application as FogMapApp).container
        // Граница суток — ДО ветки starved (day-track-history D5/D6): полночь
        // режет день строго в 00:00, новый день материализуется пустой строкой
        // даже если писать нечего; коридор через полночь не тянется.
        runCatching {
            val today = java.time.LocalDate.now()
            if (today != chunkDate) {
                trackId = container.trackRepository.openDayChunk(today)
                chunkDate = today
            }
        }
        // BURST-таймаут без новых фиксов (battery-eco 2.3): тихий возврат в сон.
        if (ecoCached && ecoProfile == EcoGovernor.Profile.BURST &&
            System.currentTimeMillis() > burstDeadlineMs && burstDeadlineMs > 0
        ) {
            enterProfile(EcoGovernor.Profile.STANDBY, verdict = "TIMEOUT")
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
            // GPS-время пишем всегда, даже в STAND без точек — иначе
            // 6 часов дома дадут 0 во всех метриках и A/B несравним.
            m[FogRepository.ECO_GPS_MS] = gpsMs
            ecoFixCount = 0
            ecoStandCount = 0
            m
        }
        val starved: Boolean = synchronized(buffer) {
            buffer.isEmpty() && rejected.isEmpty() && rawBuffer.isEmpty() && ecoSnapshot.isEmpty()
        }
        // Эко-метрики сливаем даже в starved (иначе STAND-дни невидимы).
        if (starved) {
            runCatching { container.fogRepository.recordEco(ecoSnapshot) }
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
        runCatching {
            // День уже гарантирован проверкой в начале flush (см. выше):
            // батч всегда пишется в строку текущей даты, без хвоста прошлого дня.
            if (rawBatch.isNotEmpty()) {
                container.db.rawFixDao().insertAll(rawBatch)
            }
            if (batch.isNotEmpty()) {
                val prev = container.db.trackDao().lastPoint(trackId)
                newCells = container.fogRepository.appendPoints(trackId, batch).newCells
                // Префикс спрямления (battery-eco 1.3): коридор prev -> first.
                // prev уже в БД, first — первая точка батча: прямая вместо тропинки.
                if (prev != null) {
                    val f = batch.first()
                    val d = FogRepository.haversineM(prev.lat, prev.lon, f.lat, f.lon)
                    // Пишем только значимые разрывы после тишины/STATDBY,
                    // межбатчевые 8-секундные шаги не шумят.
                    val dtS = (f.time - prev.time) / 1000
                    if (d >= 50.0 && dtS >= 60) {
                        prefixCm = (d * 100).toLong()
                        prefixN = 1L
                    }
                }
            }
            if (rej.isNotEmpty()) container.fogRepository.recordRejected(rej)
            val ecoToWrite = HashMap<String, Long>(ecoSnapshot)
            // Пробуждение БД (battery-eco 1.3): flush с реальной записью.
            ecoToWrite[FogRepository.ECO_FLUSH] = 1L
            if (prefixN > 0) {
                ecoToWrite[FogRepository.ECO_PREFIX_CM] = prefixCm
                ecoToWrite[FogRepository.ECO_PREFIX_N] = prefixN
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
                    ecoFix = payloadFix, ecoStand = payloadStand, ecoGpsMs = payloadGpsMs
                )
            )
        } else {
            DevLog.i(
                "TRACK", "flush",
                EcoLogPayload.flushPayload(
                    batch = batchSize, rejected = rejSize,
                    txnMs = txnStr, newCells = newCells,
                    mode = mode, profile = ecoProfile.name,
                    ecoFix = payloadFix, ecoStand = payloadStand, ecoGpsMs = payloadGpsMs
                )
            )
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
        // ВРЕМЕННОЕ (dev-logging): тишина Fused.
        DevLog.i(
            "TRACK", "no_fix",
            mapOf("gap_ms" to NO_FIX_GAP_MS, "mode" to ecoModeTag(), "profile" to ecoProfile.name)
        )
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

    private fun ecoModeTag(): String = if (ecoCached) "eco" else "base"

    /**
     * Решение губернатора после вердикта (battery-eco 2.2/2.3, вызывается
     * внутри synchronized): возвращает цель с причиной (дистанция до якоря
     * целым числом метров, вердикт) или null (без смены). Причина нужна
     * для eco_state — один JSONL самодостаточен для сверки (logging-gap).
     * Base всегда ACTIVE. Переподписка — снаружи, в lifecycleScope.
     */
    private data class EcoTarget(val profile: EcoGovernor.Profile, val wakeM: Long, val verdict: String)

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
        return when (ecoProfile) {
            EcoGovernor.Profile.ACTIVE -> {
                if (verdict.state == TrustEngine.State.STAND) {
                    standStreak += 1
                    if (standStreak >= EcoGovernor.STAND_CONFIRM_STREAK &&
                        now - lastStandbyEnterMs >= EcoGovernor.STANDBY_DEBOUNCE_MS
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
                    if (EcoGovernor.isWakeSignal(anchorLat, anchorLon, loc.latitude, loc.longitude)) {
                        burstDeadlineMs = now + EcoGovernor.BURST_WINDOW_MS
                        EcoTarget(EcoGovernor.Profile.BURST, distM, verdict.state.name)
                    } else {
                        // Якорь не двигаем каждый фикс: иначе окно уедет вместе
                        // с медленным пешеходом и никогда не сработает (тот же
                        // принцип что и STATIC_RADIUS в TrustEngine).
                        if (verdict.state == TrustEngine.State.MOVING) {
                            burstDeadlineMs = now + EcoGovernor.BURST_WINDOW_MS
                            EcoTarget(EcoGovernor.Profile.BURST, distM, verdict.state.name)
                        } else null
                    }
                }
            }
            EcoGovernor.Profile.BURST -> {
                if (verdict.state == TrustEngine.State.MOVING) {
                    // Префикс фиксируется во flush по коридору prev->first,
                    // здесь только переход (батарейка: без лишнего haversine).
                    val anchorLat = standbyAnchorLat
                    val anchorLon = standbyAnchorLon
                    val distM = if (anchorLat != null && anchorLon != null) {
                        FogRepository.haversineM(
                            anchorLat, anchorLon, loc.latitude, loc.longitude
                        ).toLong()
                    } else EcoLogPayload.NO_ANCHOR_M
                    EcoTarget(EcoGovernor.Profile.ACTIVE, distM, verdict.state.name)
                } else if (now > burstDeadlineMs && burstDeadlineMs > 0) {
                    standbyAnchorLat = loc.latitude
                    standbyAnchorLon = loc.longitude
                    EcoTarget(EcoGovernor.Profile.STANDBY, EcoLogPayload.NO_ANCHOR_M, verdict.state.name)
                } else null
            }
        }
    }

    /** Вход в профиль: ресаб + персист + лог (battery-eco 2.4, вне critical section). */
    private fun enterProfile(
        profile: EcoGovernor.Profile,
        force: Boolean = false,
        wakeM: Long = EcoLogPayload.NO_ANCHOR_M,
        verdict: String = "?"
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
        if (profile == EcoGovernor.Profile.STANDBY) lastStandbyEnterMs = System.currentTimeMillis()
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
                verdict = verdict
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
