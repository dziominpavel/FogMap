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
import android.os.Handler
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
    /** Wake-lock записи (change recording-mode): держится всё время записи. */
    private var trackingWake: TrackingWake.Holder? = null
    private var wakeHeld: Boolean = false
    /** Накопитель и метка события `day_branches` (field-hardening 1.2). */
    private var lastBranchLogMs: Long = 0L
    private val branchLogPending = HashMap<String, Long>()
    /** Сколько фиксов пришло отложенными заморозкой (field-hardening 1.3). */
    private var staleReplayCount: Long = 0L

    /** Метка времени предыдущего фикса. */
    @Volatile
    private var lastFixTimeMs: Long = 0L
    @Volatile
    private var lastFixGapMs: Long = 0L
    /**
     * Сериализация flush (fix-import-metrics 1.2): периодический запуск и
     * запуск по переполнению буфера не должны идти одновременно.
     */
    private val flushMutex = Mutex()
    /** Дата текущего суток-чанка (trust-v2 3.1): полночь режет чанк. */
    private var chunkDate: java.time.LocalDate = java.time.LocalDate.now()
    /** Тумблер записи (change recording-mode): false → служба останавливается. */
    @Volatile
    private var recordingCached: Boolean = true
    /** Поток heartbeat канала LIFE (fix-track-gap-visibility 1.4). */
    @Volatile
    private var heartbeatRunning: Boolean = false
    private var heartbeatThread: Thread? = null
    /** Счётчики текущей пачки: принятые фиксы и STAND-точки (метрики записи). */
    private var fixCount: Long = 0L
    private var standCount: Long = 0L
    /** GPS-время с прошлого flush (метрики записи). */
    private var lastFlushWallMs: Long = System.currentTimeMillis()
    /** Состояние движка доверия + окно истории записанных точек. */
    private var trustPrev: TrustEngine.PrevState? = null
    private val trustHistory = ArrayDeque<TrustEngine.HistPoint>()

    /**
     * Идентификаторы журнала, разобранные в текущем цикле (fix-journal 3.3):
     * помечаются обработанными в той же транзакции, что и точки, иначе смерть
     * процесса между записью и отметкой дала бы дубли.
     */
    private val inboxIdsInFlight = mutableListOf<Long>()
    /** Порция журнала за один проход: лимит переменных SQLite и размер транзакции. */
    private val inboxLimit = INBOX_BATCH

    /** Контейнер приложения: единый способ достать БД из сервиса. */
    private val appContainer: ru.fogmap.data.AppContainer
        get() = (application as FogMapApp).container

    /** Отложенный быстрый flush после новой строки журнала (D4). */
    private var quickFlushJob: Job? = null
    /** Момент последней чистки журнала: раз в час на общем тике записи. */
    private var lastPruneWallMs = 0L
    /** Переподписки подряд без фикса (change recording-mode, D7). */
    private var resubFailures = 0
    /** Для какого «последнего фикса» уже отчитались о тишине (один раз на интервал). */
    private var silenceReportedForFixWallMs = -1L

    /**
     * Прямой приём фиксов (change recording-mode, D3): второй, независимый от
     * PendingIntent путь в тот же журнал. Смерть доставки в ресивер больше не
     * останавливает запись, а смерть процесса по-прежнему лечится ресивером.
     */
    private val fixCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations
            if (locations.isEmpty()) return
            val now = System.currentTimeMillis()
            val envelopes = locations.map { loc ->
                @Suppress("DEPRECATION")
                val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
                FixEnvelope.of(
                    time = loc.time,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    acc = if (loc.hasAccuracy()) loc.accuracy else -1f,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                    isMock = mock,
                    recvMs = now
                )
            }
            lifecycleScope.launch {
                runCatching { FixInboxWriter.write(appContainer.db, envelopes) }
                    .onFailure {
                        DevLog.w(
                            "TRACK", "inbox_write_failed",
                            mapOf(
                                "n" to envelopes.size,
                                "err" to (it.javaClass.simpleName + ": " + (it.message ?: "")).take(200)
                            )
                        )
                    }
                requestFlush()
            }
        }
    }

    /** Мгновенный flush после приёма (D4): дебаунс гасит пачки. */
    private fun requestFlush() {
        if (quickFlushJob?.isActive == true) return
        quickFlushJob = lifecycleScope.launch {
            delay(QUICK_FLUSH_MS)
            flush()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Без разрешений или Play Services сервис не имеет права на FGS type=location:
        // тихо останавливаемся, НЕ роняем процесс (проверено на эмуляторе API 36).
        if (!TrackingPreconditions.playServicesAvailable(this)) {
            // Ранний выход до stopSelf: без события дыра выглядела бы как «сервис молча исчез».
            DevLog.w("TRACK", "service_init_skip", mapOf("reason" to "no_play_services"))
            stopSelf()
            return
        }
        if (!canTrack(this)) {
            DevLog.w("TRACK", "service_init_skip", mapOf("reason" to "no_permission"))
            stopSelf()
            return
        }
        try {
            startForegroundCompat(buildNotification())
        } catch (e: RuntimeException) {
            DevLog.w(
                "TRACK", "service_init_skip",
                mapOf("reason" to "foreground_exception", "err" to errText(e))
            )
            stopSelf()
            return
        }
        // Wake-lock движения (field-hardening 1.1): создаём до подписки на
        // паузу, чтобы первый applyWake уже имел держателя.
        trackingWake = TrackingWake.Holder(this)
        applyWake()
        // ВРЕМЕННОЕ (dev-logging): старт сервиса.
        DevLog.i("TRACK", "service_start", mapOf("mode" to modeTag()))
        // Канал жизни процесса (fix-track-gap-visibility 1.2/1.4): база для
        // `freeze_gap` и heartbeat отдельным потоком. Поток не зависит от
        // flush-цикла: его молчание означает «процесс не планируется», а
        // молчание только flush-цикла видно как freeze_gap при живом heartbeat.
        TrackingLife.onServiceStart()
        TrackingHealth.onServiceStart(System.currentTimeMillis())
        startHeartbeat()
        lifecycleScope.launch {
            val container = (application as FogMapApp).container
            // Подписка на паузу: при включенной паузе точки не пишем.
            // Флаг дублируется в recordingCached, чтобы onRawLocation оставался
            // синхронным (вердикт + запись без гонок между колбэками).
            // Эко всегда включен (eco-always-on): тумблеров нет.
            launch {
                // Тумблер записи (change recording-mode): выключили — службы нет.
                container.settingsRepository.recordingEnabled.collect { recording ->
                    // Поток эмитит на любое изменение настроек (тема, границы,
                    // диагностика) — реагируем только на смену тумблера.
                    if (recording == recordingCached) return@collect
                    recordingCached = recording
                    if (!recording) {
                        DevLog.i("TRACK", "recording_disabled", mapOf("outcome" to "stop_self"))
                        stopSelf()
                        return@collect
                    }
                    applyWake()
                    updateNotification()
                }
            }
            // День-атом (day-track-history D1/D5): переоткрываем строку даты,
            // а не плодим обломок на каждый старт. Пустой день материализуется
            // строкой с нулями даже до первой движущейся точки.
            trackId = container.trackRepository.openDayChunk()
            chunkDate = java.time.LocalDate.now()
            // Контекст доверия после рестарта (fix-journal D4): окно 60 с
            // поднимается из точек дня, иначе первая точка после пробуждения
            // судится как холодный старт и рвёт непрерывность.
            restoreTrust(container)
            // Нули веток дня видимы с первого момента (6.2), не после flush.
            runCatching { container.fogRepository.recordBranches(emptyMap(), chunkDate) }
            lastFlushWallMs = System.currentTimeMillis()
            // ВРЕМЕННОЕ (dev-logging): чанк старта (только id, без координат).
            DevLog.i("TRACK", "day_chunk", mapOf("track_id" to trackId, "mode" to modeTag()))
            // Выключенный тумблер не должен оставлять ни подписки, ни службы:
            // подписка — это и есть расход батареи (change recording-mode).
            val recording = runCatching {
                container.settingsRepository.recordingEnabled.first()
            }.getOrDefault(true)
            if (!recording) {
                DevLog.i("TRACK", "recording_disabled", mapOf("outcome" to "no_subscribe"))
                stopSelf()
                return@launch
            }
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
        // Замер задержки старта движения: зазор по МЕТКАМ фиксов, а не по
        // часам приёма. Пачка после разморозки приходит одной секундой, и
        // «по приёму» зазор был бы нулевым — замер терял смысл (поле 10.11).
        val nowFix = System.currentTimeMillis()
        lastFixGapMs = if (lastFixTimeMs > 0) (loc.time - lastFixTimeMs).coerceAtLeast(0L) else 0L
        lastFixTimeMs = loc.time
        // Фикс пришёл — счётчик неудач восстановления сброшен (D7).
        TrackingHealth.onFix(nowFix)
        resubFailures = 0
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
            synchronized(buffer) {
                rejected[reason.key] = (rejected[reason.key] ?: 0) + 1
                fixCount += 1
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
        val nowMs = System.currentTimeMillis()
        val lastRecv = synchronized(buffer) { trustHistory.lastOrNull()?.recv }
        // gps-trust: зазоры вердикта считаются по моменту приёма, а не по
        // Location.getTime() — провайдер отдает повторяющиеся метки фикса.
        // Исключение — отложенная заморозкой пачка (field-hardening 1.3):
        // у неё приём один на все фиксы, и без метки фикса доверие видит
        // «35 точек за 0 секунд» (прыжки/телепорты).
        val recv = StaleReplay.recvFor(nowMs, loc.time, lastRecv)
        if (StaleReplay.replayed(nowMs, recv)) {
            synchronized(buffer) { staleReplayCount += 1 }
        }
        val hp = TrustEngine.HistPoint(
            time = loc.time, lat = loc.latitude, lon = loc.longitude, acc = loc.accuracy,
            speed = speedOrNull,
            recv = recv
        )
        synchronized(buffer) {
            if (!recordingCached) {
                rejected[FogRepository.REJECT_PAUSED] =
                    (rejected[FogRepository.REJECT_PAUSED] ?: 0) + 1
                fixCount += 1
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
                fixCount += 1
                countBranches(verdict)
                if (verdict.resetHistory) {
                    val keep = trustHistory.lastOrNull()
                    trustHistory.clear()
                    if (keep != null) trustHistory.addLast(keep)
                }
                trustHistory.addLast(hp)
                TrustEngine.pruneHistory(trustHistory, hp.recv)
                rawBuffer.add(
                    rawOf(
                        loc = loc, accOrDef = accOrDef, speedOrNull = speedOrNull,
                        isMock = isMock, filter = reason.key,
                        state = verdict.state.name, trust = verdict.trust,
                        openFog = 0, rejectReason = verdict.countReject,
                        history = historySnap, prev = prevSnap
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
            fixCount += 1
            if (verdict.state == TrustEngine.State.STAND) standCount += 1
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
            TrustEngine.pruneHistory(trustHistory, hp.recv)
            // Черный ящик: вердикт пишется всегда, включая STAND.
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
            // День-атом (day-track-history D2): точка пишется всегда — STAND
            // просто не открывает туман, но линия трека непрерывна.
            buffer.add(
                RawPoint(
                    time = loc.time, lat = loc.latitude, lon = loc.longitude,
                    acc = loc.accuracy, speed = if (loc.hasSpeed()) loc.speed else null,
                    trust = verdict.trust, openFog = verdict.openFog,
                    state = verdict.state.name, rejectReason = verdict.countReject
                )
            )
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
        history = history, prev = prev, recv = System.currentTimeMillis()
    )

    /**
     * Чистка разобранных строк журнала (change recording-mode, D3): раньше
     * `prune` звал только ресивер, и при мёртвой доставке в PendingIntent
     * журнал рос бы неограниченно — ровно в том сценарии, ради которого
     * второй путь и появился. Раз в час, из общего тика записи.
     */
    private suspend fun pruneInboxIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPruneWallMs < PRUNE_INTERVAL_MS) return
        lastPruneWallMs = now
        runCatching { FixInboxWriter.prune(appContainer.db, now) }
    }

    /**
     * Разбор журнала в живой конвейер (fix-journal D2/D4). Порядок — по метке
     * фикса; календарная дата точки уже посчитана при приёме и здесь не
     * пересчитывается, поэтому ночная пачка не теряет свой день.
     */
    private suspend fun drainInbox() {
        val dao = appContainer.db.fixInboxDao()
        val rows = runCatching { dao.pendingBatch(inboxLimit) }.getOrNull().orEmpty()
        if (rows.isEmpty()) return
        for (row in rows) {
            synchronized(buffer) { inboxIdsInFlight.add(row.id) }
            if (row.source == FixEnvelope.SOURCE_MOCK) {
                // Мок-фикс не делаем Location: помечаем отброс и строку черного ящика.
                synchronized(buffer) {
                    rejected[FogRepository.REJECT_MOCK] =
                        (rejected[FogRepository.REJECT_MOCK] ?: 0) + 1
                    fixCount += 1
                    rawBuffer.add(
                        RawTrace.build(
                            time = row.fixTime, lat = row.lat, lon = row.lon,
                            acc = row.acc, speed = row.speed, isMock = true,
                            filter = FogRepository.REJECT_MOCK, state = null, trust = null,
                            openFog = null, rejectReason = null, history = emptyList()
                        )
                    )
                }
            } else {
                onRawLocation(locationOf(row))
            }
        }
        DevLog.i(
            "TRACK", "inbox_drain",
            mapOf("rows" to rows.size, "pending" to runCatching { dao.pendingCount() }.getOrDefault(0L))
        )
    }

    /**
     * Восстановление контекста вердикта из БД (fix-journal D4): окно истории
     * 60 с и последнее состояние. Память — кэш, а не хранилище: после смерти
     * процесса непрерывность восстанавливается из точек дня.
     */
    private suspend fun restoreTrust(container: ru.fogmap.data.AppContainer) {
        runCatching {
            val dao = container.db.trackDao()
            val points = dao.pointsOf(trackId)
            val last = points.lastOrNull() ?: return@runCatching
            val lastState = runCatching { TrustEngine.State.valueOf(last.state) }
                .getOrDefault(TrustEngine.State.MOVING)
            trustPrev = TrustEngine.PrevState(
                state = lastState,
                trust = last.trust,
                anchorLat = last.lat,
                anchorLon = last.lon,
                kind = MotionKindClassifier.classify(last.speed, null)
            )
            val from = last.time - TrustEngine.HISTORY_MAX_AGE_MS
            val tail = points.filter { it.time >= from }
            synchronized(buffer) {
                trustHistory.clear()
                for (p in tail) {
                    trustHistory.addLast(
                        TrustEngine.HistPoint(
                            time = p.time, lat = p.lat, lon = p.lon,
                            acc = p.acc, speed = p.speed, recv = p.time
                        )
                    )
                }
            }
            DevLog.i(
                "TRACK", "trust_restored",
                mapOf("points" to tail.size, "state" to lastState.name, "trust" to last.trust)
            )
        }
    }

    /**
     * Возврат пачки в буферы после неудачной записи (fix-journal 5.1):
     * упавший коммит не должен уносить данные. Порядок восстанавливается
     * в начало очереди, счётчики складываются обратно.
     */
    private fun requeueFlush(
        batch: List<RawPoint>,
        rej: Map<String, Long>,
        rawBatch: List<RawFixEntity>,
        inboxIds: List<Long>,
        eco: Map<String, Long>
    ) {
        synchronized(buffer) {
            if (batch.isNotEmpty()) buffer.addAll(0, batch)
            if (rawBatch.isNotEmpty()) rawBuffer.addAll(0, rawBatch)
            if (inboxIds.isNotEmpty()) inboxIdsInFlight.addAll(0, inboxIds)
            for ((key, n) in rej) rejected[key] = (rejected[key] ?: 0) + n
            eco[FogRepository.ECO_FIX]?.let { fixCount += it }
            eco[FogRepository.ECO_STAND]?.let { standCount += it }
        }
    }

    /** Строка журнала как Location для существующего конвейера вердикта. */
    private fun locationOf(row: ru.fogmap.data.db.FixInboxEntity): Location =
        Location("fused").apply {
            time = row.fixTime
            latitude = row.lat
            longitude = row.lon
            accuracy = row.acc
            row.speed?.let { speed = it }
        }

    private suspend fun flush() {
        // Сериализация (fix-import-metrics 1.2): таймер и переполнение буфера
        // не пересекаются, дистанция/время/клетки не двоятся.
        flushMutex.withLock { flushLocked() }
    }

    /** Тело flush под [flushMutex]: чтение буферов, транзакция, метрики, лог. */
    private suspend fun flushLocked() {
        val container = (application as FogMapApp).container
        // Журнал первичен (fix-journal D2/D4): то, что система успела доставить,
        // разбирается до записи пачки — иначе фиксы ждали бы следующего тика.
        drainInbox()
        pruneInboxIfDue()
        // Граница суток — ДО ветки starved (day-track-history D5/D6): полночь
        // режет день строго в 00:00, новый день материализуется пустой строкой
        // даже если писать нечего; коридор через полночь не тянется.
        runCatching {
            val today = java.time.LocalDate.now()
            if (today != chunkDate) {
                // Аудит закрываемого дня (day-ledger 2.3): пересчёт из точек и
                // ремонт расхождений ДО лога и переключения чанка.
                runCatching {
                    val res = ru.fogmap.data.DayAuditRepository(container.db).audit(chunkDate)
                    DevLog.i(
                        "TRACK", "audit",
                        mapOf(
                            "day" to chunkDate.toString(),
                            "ok" to if (res.ok) 1 else 0,
                            "points" to (res.totals?.points ?: 0),
                            "fixed_points" to if (res.fixedPoints) 1 else 0,
                            "fixed_time" to if (res.fixedTime) 1 else 0,
                            "fixed_distance" to if (res.fixedDistance) 1 else 0,
                            "moved" to res.movedForeign
                        )
                    )
                }
                // Закрытие дня (fix-track-gap-visibility 4.4): дневные счётчики
                // пишутся в лог ДО переключения чанка, пока trackId и counters
                // еще принадлежат закрываемому дню.
                logDayStats(chunkDate, trackId, via = "date_change")
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
        // GPS-время с прошлого flush: весь интервал сервис держал подписку.
        // Необрезанный интервал — еще и метрика отставания цикла.
        val nowWall = System.currentTimeMillis()
        val sinceLastMs = (nowWall - lastFlushWallMs).coerceAtLeast(0L)
        val gpsMs = sinceLastMs.coerceIn(0L, FLUSH_INTERVAL_MS * 2)
        lastFlushWallMs = nowWall
        // Канал жизни (1.5): тик записи отмечается на каждой итерации, включая
        // пустую; разрыв сверх порога даст LIFE/freeze_gap.
        TrackingLife.onFlushTick()
        // suspend-вызовы — строго вне synchronized (иначе critical section).
        val ecoSnapshot: Map<String, Long> = synchronized(buffer) {
            val m = HashMap<String, Long>()
            if (fixCount > 0) m[FogRepository.ECO_FIX] = fixCount
            if (standCount > 0) m[FogRepository.ECO_STAND] = standCount
            // GPS-время пишем всегда, даже когда точек нет.
            m[FogRepository.ECO_GPS_MS] = gpsMs
            fixCount = 0
            standCount = 0
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
        // Здоровье записи (change recording-mode, D7): сначала чиним подписку,
        // потом пишем счётчики — иначе «нет фиксов» не видно вовсе.
        checkHealth()
        // Тишина Fused (D7): интервал считается по возрасту последнего ФИКСА,
        // а не тика записи — живой flush-цикл больше не маскирует мёртвую доставку.
        checkSilence(TrackingHealth.ageMs(nowWall))
        // Эко/ветки сливаем даже в starved (нули веток видимы всегда, 6.2).
        if (starved) {
            // Строки журнала, разобранные без точек и отбросов (STAND в STANDBY,
            // сэмплированный сыряк): их исход окончательный, помечаем здесь.
            // Иначе журнал растёт вечно — полевой прогон 09.11 дал 1236 строк.
            val idleIds = synchronized(buffer) {
                val copy = inboxIdsInFlight.toList()
                inboxIdsInFlight.clear()
                copy
            }
            if (idleIds.isNotEmpty()) {
                runCatching { container.db.fixInboxDao().markDone(idleIds) }
            }
            runCatching { container.fogRepository.recordEco(ecoSnapshot) }
            runCatching { container.fogRepository.recordBranches(branchSnapshot, chunkDate) }
            logBranches(branchSnapshot)
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
        // Идентификаторы журнала, разобранные в этом цикле: помечаются в той же
        // транзакции, что и точки (fix-journal 3.3) — дублей после смерти нет.
        val inboxIds: List<Long> = synchronized(buffer) {
            val copy = inboxIdsInFlight.toList()
            inboxIdsInFlight.clear()
            copy
        }
        // ВРЕМЕННОЕ (dev-logging): замер транзакции flush (только counts, без координат).
        val t0 = System.nanoTime()
        var newCells = 0
        val batchSize = batch.size
        val rejSize = rej.values.sum()
        try {
            if (rawBatch.isNotEmpty()) {
                container.db.rawFixDao().insertAll(rawBatch)
            }
            var idsToMark = inboxIds
            if (batch.isNotEmpty()) {
                // День точки, а не «сейчас» (fix-stale-batch-day): отложенная
                // заморозкой пачка приходит разом уже после полуночи, и раньше
                // вся она уходила в строку НОВОГО дня — 08.11 01:39 в трек
                // «8 ноября» легли 89 точек вечера 07.11, а вечер 07.11 остался
                // дырой. Каждые сутки пишутся в свою строку, от старых к новым.
                val dayGroups = FogRepository.groupByDay(batch)
                for ((day, dayPoints) in dayGroups) {
                    // Строка дня: кэш сервиса валиден только для текущих суток,
                    // иначе find-or-create по дате самих точек (морозка через
                    // полночь, рестарт, импорт).
                    val tid = if (day == chunkDate) trackId
                    else container.trackRepository.openDayChunk(day)
                    val appended = container.fogRepository.appendPoints(
                        tid, dayPoints, date = day, inboxIds = idsToMark
                    )
                    idsToMark = emptyList()
                    newCells += appended.newCells
                    // Страховка той же защиты внутри appendPoints (гонка с rebuild):
                    // подтягиваем фактический id, чтобы next flush читал хвост дня.
                    if (day == chunkDate && appended.trackId != trackId) {
                        trackId = appended.trackId
                        DevLog.w(
                            "TRACK", "day_chunk_recovered",
                            mapOf("track_id" to trackId, "cause" to "stale_id")
                        )
                    }
                }
            }
            // Строки журнала, не давшие точек (только отбросы или сыряк):
            // исход окончательный, иначе они останутся в очереди навсегда.
            if (idsToMark.isNotEmpty()) {
                container.db.fixInboxDao().markDone(idsToMark)
                idsToMark = emptyList()
            }
            if (rej.isNotEmpty()) container.fogRepository.recordRejected(rej)
            container.fogRepository.recordBranches(branchSnapshot, chunkDate)
            val ecoToWrite = HashMap<String, Long>(ecoSnapshot)
            // Пробуждение БД (battery-eco 1.3): flush с реальной записью.
            ecoToWrite[FogRepository.ECO_FLUSH] = 1L
            container.fogRepository.recordEco(ecoToWrite)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена — не повод терять пачку (fix-journal 5.1/5.2): возвращаем
            // в буферы и пробрасываем дальше, чтобы корутина умерла честно.
            requeueFlush(batch, rej, rawBatch, inboxIds, ecoSnapshot)
            throw e
        } catch (e: Throwable) {
            requeueFlush(batch, rej, rawBatch, inboxIds, ecoSnapshot)
            DevLog.w(
                "TRACK", "flush_failed",
                mapOf("err" to (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200))
            )
        }
        val txnMs = (System.nanoTime() - t0) / 1e6
        val mode = modeTag()
        val txnStr = String.format(Locale.US, "%.1f", txnMs)
        // Эко-снимок пачки в лог (logging-gap 1.1): те же числа что в счетчики —
        // один JSONL самодостаточен для сверки без доступа к БД.
        val payloadFix = ecoSnapshot[FogRepository.ECO_FIX] ?: 0L
        val payloadStand = ecoSnapshot[FogRepository.ECO_STAND] ?: 0L
        val payloadGpsMs = ecoSnapshot[FogRepository.ECO_GPS_MS] ?: 0L
        // Отложенная заморозкой пачка (field-hardening 1.3): число видно в логе.
        val payloadStale: Long = synchronized(buffer) {
            val v = staleReplayCount
            staleReplayCount = 0
            v
        }
        // Одно место сборки payload: раньше одинаковый вызов стоял в обеих
        // ветках, и правка полей означала правку дважды.
        val flushPayload = EcoLogPayload.flushPayload(
            batch = batchSize, rejected = rejSize,
            txnMs = txnStr, newCells = newCells,
            mode = mode,
            ecoFix = payloadFix, ecoStand = payloadStand, ecoGpsMs = payloadGpsMs,
            sinceLastMs = sinceLastMs,
            bootMonoMs = DevLog.bootMonoMs(),
            staleReplay = payloadStale.takeIf { it > 0 }
        )
        // Медленная транзакция — отдельное событие W, иначе flush уровня I.
        if (txnMs > 500.0) DevLog.w("TRACK", "slow_flush", flushPayload)
        else DevLog.i("TRACK", "flush", flushPayload)
        if (branchSnapshot.isNotEmpty()) {
            logBranches(branchSnapshot)
        }
    }

    /**
     * Лог счётчиков веток (fix-track-gap-visibility 4.1): только когда счётчики
     * дня изменились, то есть пачка не пуста. Пустой тик — 58 % файла 21.10;
     * полнота в `counters.json` от гейта не зависит: `recordBranches` с нулями
     * вызывается всегда, включая ветку starved.
     */
    private fun logBranches(counts: Map<String, Long>) {
        if (!BranchLogGate.changed(counts)) return
        // Field-hardening 1.2: счётчики в БД пишутся на каждом flush, а событие
        // лога — не чаще раза в 5 минут и сведённым итогом. Ни одно значение
        // не теряется: между событиями пачки складываются в накопитель.
        synchronized(branchLogPending) { BranchLogGate.merge(branchLogPending, counts) }
        val now = System.currentTimeMillis()
        val due = synchronized(branchLogPending) {
            BranchLogGate.shouldLog(lastBranchLogMs, now, branchLogPending)
        }
        if (!due) return
        val merged: Map<String, Long> = synchronized(branchLogPending) {
            val copy = branchLogPending.toMap()
            branchLogPending.clear()
            copy
        }
        lastBranchLogMs = now
        DevLog.i("TRACK", "day_branches", EcoLogPayload.branchPayload(merged))
    }

    /**
     * Тишина Fused (fix-track-gap-visibility 2.1/2.2): разрыв считается от
     * времени последнего подтвержденного тика записи, а не от пустоты буфера.
     * Заморозка процесса или убитый flush-цикл видны как тишина, даже если
     * после разморозки пришел пакет фиксов. Один интервал = один счетчик:
     * следующий тик перезаписывает [lastFlushWallMs], повторного счета нет.
     */
    private suspend fun checkSilence(gapMs: Long) {
        // recordingCached обновляется подпиской на настройки в onCreate; к первому
        // тику (через 15 с) он уже заполнен, suspend-чтение здесь не нужно.
        if (!SilenceGap.shouldReport(gapMs, !recordingCached, canTrack(this))) return
        // Один интервал = один счётчик: повторно отчитываемся только после
        // нового фикса (change recording-mode, D7).
        if (silenceReportedForFixWallMs == TrackingHealth.lastFixWallMs) return
        silenceReportedForFixWallMs = TrackingHealth.lastFixWallMs
        val container = (application as FogMapApp).container
        runCatching {
            container.fogRepository.recordRejected(mapOf(FogRepository.REJECT_NO_FIX to 1))
        }
        // ВРЕМЕННОЕ (dev-logging): тишина Fused — фактическая длительность,
        // а не порог срабатывания (fix-eco-signal-loss 3.1/dev-logging).
        DevLog.i(
            "TRACK", "no_fix",
            EcoLogPayload.noFixPayload(gapMs, modeTag())
        )
    }

    /**
     * Здоровье записи (change recording-mode, D7): переподписка по возрасту
     * фикса, затем перезапуск службы, затем пауза до кулдауна.
     *
     * Проверка идёт на каждом тике записи: пока служба жива, она сама замечает,
     * что доставка умерла, и не ждёт открытия приложения владельцем.
     */
    private fun checkHealth() {
        val now = System.currentTimeMillis()
        val decision = FixSilence.decide(
            nowMs = now,
            sinceMs = TrackingHealth.sinceMs(),
            recording = recordingCached,
            failures = resubFailures,
            lastRestartMs = TrackingHealth.lastSelfRestartWallMs
        )
        when (decision.action) {
            FixSilence.Action.NONE -> Unit
            FixSilence.Action.RESUBSCRIBE -> {
                resubFailures = decision.failures
                DevLog.w(
                    "TRACK", "fix_silence",
                    mapOf(
                        "action" to "resubscribe",
                        "age_ms" to decision.ageMs,
                        "failures" to decision.failures
                    )
                )
                resubscribe()
            }
            FixSilence.Action.RESTART -> {
                resubFailures = 0
                TrackingHealth.lastSelfRestartWallMs = now
                DevLog.w(
                    "TRACK", "self_restart",
                    mapOf("age_ms" to decision.ageMs, "via" to "fix_silence")
                )
                // Останавливаемся и поднимаемся заново: Handler живёт в процессе,
                // поэтому старт не отменится вместе с уничтожением службы.
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { start(applicationContext, via = "self") }
                }, 1_000L)
                stopSelf()
            }
            FixSilence.Action.GIVE_UP -> {
                // Кулдаун: следующий перезапуск не раньше, чем через 5 минут.
                DevLog.w(
                    "TRACK", "fix_silence",
                    mapOf("action" to "give_up", "age_ms" to decision.ageMs)
                )
            }
        }
    }

    /**
     * Дневные счётчики в лог (fix-track-gap-visibility 4.4): источник тот же,
     * что у `counters.json` — таблица counters (ключи `*_day_<date>`); число
     * точек — из строки дня (`tracks.pointsCount`), в counters его нет.
     */
    private suspend fun logDayStats(date: java.time.LocalDate, trackId: Long, via: String) {
        val container = (application as FogMapApp).container
        val all = runCatching {
            container.db.counterDao().all().associate { it.key to it.value }
        }.getOrNull() ?: return
        val stats = FogRepository.dayCounters(all, date)
        val points = runCatching {
            container.db.trackDao().trackById(trackId)?.pointsCount ?: 0
        }.getOrDefault(0)
        DevLog.i(
            "TRACK", "day_stats",
            EcoLogPayload.dayStatsPayload(
                date = date.toString(),
                distCm = stats.distCm,
                timeS = stats.timeS,
                points = points,
                areaCells = stats.areaCells,
                flush = stats.flush,
                rejected = stats.rejected,
                via = via
            )
        )
    }

    /**
     * Heartbeat канала LIFE (fix-track-gap-visibility 1.4): отдельный поток,
     * а не flush-цикл. Пока процесс планируется, событие идет каждые 5 минут
     * (в Doze система может задержать поток — фактический интервал виден по
     * `since_last_heartbeat_ms`). Если цикл записи при этом молчит, поток
     * фиксирует `freeze_gap` со стороны heartbeat.
     */
    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        heartbeatThread = Thread({
            while (heartbeatRunning) {
                try {
                    Thread.sleep(TrackingLife.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!heartbeatRunning) break
                runCatching { TrackingLife.heartbeat("thread") }
                runCatching { TrackingLife.checkFlushSilence() }
            }
        }, "TrackingHeartbeat").apply { isDaemon = true; start() }
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
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
        // Закрытие дня при остановке сервиса (fix-track-gap-visibility 4.4):
        // дневные счётчики в лог, пока строка дня еще текущая.
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1500L) {
                    logDayStats(chunkDate, trackId, via = "service_stop")
                }
            }
        }
    }

    /**
     * Подписка на фиксы через PendingIntent (fix-journal D1): доставку
     * выполняет система, поднимая процесс на каждом broadcast, а не колбэк
     * внутри живого сервиса. Заморозка процесса больше не теряет приём.
     */
    private fun subscribeFused() {
        val params = RecordingParams.fused()
        val request = LocationRequest.Builder(params.priority, params.intervalMs)
            .setMinUpdateIntervalMillis(params.minIntervalMs)
            .setMinUpdateDistanceMeters(params.distanceM)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(this).apply {
                // Путь 1: PendingIntent поднимает процесс, если его убили.
                requestLocationUpdates(request, fixPendingIntent())
                // Путь 2: прямой колбэк, если доставка в ресивер молчит.
                requestLocationUpdates(request, fixCallback, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    /** Broadcast-приёмник журнала: mutable — система дописывает extras результата. */
    private fun fixPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0,
        Intent(this, LocationFixReceiver::class.java).setAction(LocationFixReceiver.ACTION_FIX),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Переподписка (change recording-mode, D7): единая точка для восстановления. */
    private fun resubscribe() {
        unsubscribe()
        subscribeFused()
    }

    /**
     * Снять ОБЕ подписки (change recording-mode, D3): колбэк и PendingIntent
     * регистрируются вместе, поэтому и снимаются вместе — переподписка и
     * уничтожение службы идут одним путём.
     */
    private fun unsubscribe() {
        runCatching {
            LocationServices.getFusedLocationProviderClient(this).apply {
                removeLocationUpdates(fixPendingIntent())
                removeLocationUpdates(fixCallback)
            }
        }
    }

    /** Тег режима записи в логах (change recording-mode): один профиль. */
    private fun modeTag(): String = "hard"

    /**
     * Держим CPU всё время записи (change recording-mode, D8).
     *
     * FGS на Huawei не мешает системе вытеснить процесс: цикл записи вставал
     * на часы, а движение терялось. Остановка CPU — один из путей тихой потери
     * фиксов, поэтому lock держится, пока включён тумблер, и снимается сразу
     * при выключении. Событие пишется только на смене состояния.
     */
    private fun applyWake() {
        val holder = trackingWake ?: return
        val want = TrackingWake.neededFor(recordingCached)
        val held = runCatching { holder.setHeld(want) }.getOrDefault(false)
        if (held == wakeHeld) return
        wakeHeld = held
        DevLog.i("TRACK", "wakelock", mapOf("held" to held))
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
        // Wake-lock не переживает сервис (field-hardening 1.1).
        trackingWake?.setHeld(false)
        wakeHeld = false
        // ВРЕМЕННОЕ (dev-logging): остановка сервиса.
        DevLog.i("TRACK", "service_stop", mapOf("mode" to modeTag()))
        TrackingHealth.onServiceStop()
        stopHeartbeat()
        unsubscribe()
        flushBlocking()
        // Метка жизни на диск: разрыв до следующего proc_start будет точным.
        TrackingLife.flushMarker()
        flushJob?.cancel()
        super.onDestroy()
    }

    // --- Статусное уведомление «пишет трек» (trust-v2 4.1: без действий,
    // пауза только из Настроек) ---

    /**
     * Канал уведомления записи. IMPORTANCE_MIN — самый тихий режим, который
     * вообще существует: без иконки в строке состояния, без звука и вибрации,
     * строкой в самом низу шторки. Полностью убрать уведомление нельзя:
     * Android требует его у любой foreground-службы.
     *
     * Важность канала неизменяема после создания, поэтому канал пересоздан
     * под новым id, а старый удаляется.
     */
    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.deleteNotificationChannel(CHANNEL_OLD) }
        val channel = NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_MIN)
            .apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        nm.createNotificationChannel(channel)
    }

    /**
     * Уведомление службы: она живёт только при включённой записи, поэтому
     * «состояния паузы» у него нет — есть состояние записи (change recording-mode).
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("FogMap пишет трек")
            .setContentText("Туман открывается")
            .setSmallIcon(R.drawable.ic_stat_fog)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            // Страховка на OEM, где «несмахиваемое» всё же смахивается:
            // снятие уведомления не должно тихо останавливать запись.
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    this, 3, Intent(this, NotificationDismissReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
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

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    companion object {
        const val CHANNEL = "tracking_v2"
    /** Старый канал с IMPORTANCE_LOW удаляется при создании нового. */
    const val CHANNEL_OLD = "tracking"
        const val NOTIF_ID = 41
        /** Flush-интервал (tracking-reliability 2.1): потери при убийстве — не больше него. */
        const val FLUSH_INTERVAL_MS = 15_000L
        /** Быстрый flush после новой строки журнала (change recording-mode, D4). */
        const val QUICK_FLUSH_MS = 1_000L
        /** Как часто чистить разобранные строки журнала. */
        const val PRUNE_INTERVAL_MS = 60 * 60_000L
    /** Порция журнала за проход (fix-journal 3.2). */
    const val INBOX_BATCH = 200
        const val FLUSH_SIZE = 20

        /** Трекинг разрешен, только когда есть гео-разрешение (spec: «когда разрешено»). */
        fun canTrack(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        /**
         * Текст ошибки для DevLog (diag-start-failures): только класс и
         * обрезанное сообщение, без intent-экстров и координат (design D6).
         */
        fun errText(e: Throwable): String =
            (e.javaClass.simpleName + ": " + (e.message ?: "")).take(ERR_MSG_MAX)

        const val ERR_MSG_MAX = 200

        /**
         * Старт/перезапуск трекинга. Каждая попытка оставляет след
         * `TRACK/start_attempt` с исходом (diag-start-failures): без него
         * отказ `startForegroundService` из фона выглядел бы как пустое окно
         * в таймлайне. `via` — кто пытался стартовать (design D2).
         * Возвращает true, если попытка была предпринята без отказа платформы.
         */
        fun start(context: Context, via: String = "code"): Boolean {
            if (!canTrack(context)) return failStart(via, null)
            val i = Intent(context, TrackingService::class.java)
            val err = runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                else context.startService(i)
            }.exceptionOrNull()
            if (err == null) {
                DevLog.i("TRACK", "start_attempt", mapOf("via" to via, "outcome" to "started"))
                return true
            }
            return failStart(via, err)
        }

        /** Отказ старта (diag-start-failures 1.1): класс + обрезанный текст. */
        private fun failStart(via: String, e: Throwable?): Boolean {
            DevLog.w(
                "TRACK", "start_attempt",
                mapOf(
                    "via" to via, "outcome" to "start_failed",
                    "err_class" to (e?.javaClass?.simpleName ?: "NoLocationPermission"),
                    "err_msg" to (e?.message ?: "").take(ERR_MSG_MAX)
                )
            )
            return false
        }
    }
}
