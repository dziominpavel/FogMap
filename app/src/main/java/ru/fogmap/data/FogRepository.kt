package ru.fogmap.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.data.db.VisitedCell
import ru.fogmap.fog.FogGrid
import ru.fogmap.fog.FogGrid.Cell
import ru.fogmap.region.RegionGeometry
import ru.fogmap.tracking.PendingGate
import ru.fogmap.tracking.TrustEngine
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RawPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float?,
    /** Доверие 0–100 из TrustEngine (дефолт = доверенная, для старых вызовов). */
    val trust: Int = 100,
    /**
     * Кандидат ворот C (trust-v2 2.1): вердикт разрешает открытие, но туман
     * откроется только подтверждением (лаг). false = вердиктное вето сразу.
     */
    val openFog: Boolean = true,
    /** State вердикта (trust-v2 1.2): STAND/MOVING/SUSPECT, для переобработки. */
    val state: String = "MOVING",
    /** Причина подозрения/отброса (ключи REJECT_*), null = чистая точка. */
    val rejectReason: String? = null
)

/**
 * План ремонта одного трека (fix-pending-gate-false-vetoes E): что переоткрыть
 * ([confirm]), кого чем закрыть заново ([veto]: id точки → причина REJECT_*).
 * Чистые данные — план считается без БД, применяется в транзакции.
 */
data class RepairPlan(
    val confirm: List<TrackPointEntity>,
    val veto: Map<Long, String>
)

/** Итог ремонтного прохода для DevLog/аудита. */
data class RepairSummary(
    val tracks: Int,
    val points: Int,
    val cells: Int
)

/** Хвост прошлого батча как RawPoint для честной дистанции (trust учитывается). */
internal fun TrackPointEntity.toRaw() =
    RawPoint(time, lat, lon, acc, speed, trust, openFog = fogOpened == 1, state, rejectReason)

/**
 * Транзакция «батч точек → ячейки → счетчики» (задача 2.3).
 * Атомарность «точка → туман → статистика»: всё в одной Room-транзакции.
 */
class FogRepository(
    private val db: AppDatabase,
    private val achievements: AchievementRepository? = null
) {

    /**
     * @param trackId фактическая строка дня, в которую легли точки: совпадает с
     * аргументом, кроме случая протухшего id (см. [appendPoints]) — вызывающий
     * обновляет свой кэш по этому полю.
     */
    data class BatchResult(val newCells: Int, val distanceM: Double, val trackId: Long)

    suspend fun appendPoints(
        trackId: Long,
        points: List<RawPoint>,
        date: LocalDate = LocalDate.now(),
        wakeAnchor: RawPoint? = null
    ): BatchResult {
        if (points.isEmpty()) return BatchResult(0, 0.0, trackId)
        var newBase = 0
        var distance = 0.0
        var resolvedTrackId = trackId
        db.withTransaction {
            // 0. Защита от протухшего id (fix-stale-track-id 24.09): импорт/rebuild
            // (track-debug) или удаление трека пересоздают строку дня, пока сервис
            // держит старый trackId в поле — без проверки точки стали бы сиротами
            // (вечерние маршруты 24.09 пропали из истории при живом raw). Проверка
            // ВНУТРИ этой транзакции закрывает гонку с многосекундным rebuild-ом:
            // write-лок SQLite сериализует её с импортом.
            if (db.trackDao().trackById(trackId) == null) {
                resolvedTrackId = TrackRepository(db).openDayChunk(date)
            }
            val tid = resolvedTrackId
            // 0б. План батча (fix-import-metrics 1.3): дистанция — ТОЛЬКО от
            // последней записанной точки дня; якорь пробуждения (wake-balance
            // 2.2) — инструмент коридора тумана. Его хорда «якорь -> первая
            // точка» в дистанцию не входит (21.09: 19.59 км против 11.24 км
            // по сохранённым точкам).
            val plan = planBatch(
                dayTail = db.trackDao().lastPoint(tid)?.toRaw(),
                wakeAnchor = wakeAnchor
            )
            // 1. Точки в БД пачкой — сырые, с полным вердиктом (ворота разделены:
            // линия трека сохраняется всегда). fogOpened: кандидаты ворот C — 0
            // (откроются подтверждением ниже), вердиктные вето — 2 (навсегда).
            db.trackDao().insertPoints(points.map {
                TrackPointEntity(
                    trackId = tid, time = it.time, lat = it.lat,
                    lon = it.lon, acc = it.acc, speed = it.speed, trust = it.trust,
                    state = it.state, rejectReason = it.rejectReason,
                    fogOpened = if (it.openFog) 0 else 2
                )
            })
            // 2. Ворота C: разбираем хвост ожидания (старые pending + свежий
            // батч) — подтвержденные открывают туман, заветированные закрыты.
            // Последние LAG точек всегда остаются ждать будущего.
            newBase = confirmPending(resolvedTrackId, points, plan.corridorAnchor)
            // 3. Дистанция/время — только MOVING (fix-walk-fog-verdict 4.2):
            // STAND-строки не накручивают км/мин.
            val moving = points.filter { it.state == "MOVING" }
            val distTail = plan.distanceTail?.takeIf { it.state == "MOVING" }
            distance = batchDistance(moving, distTail)
            // 3б. Честная статистика трека — в той же транзакции (баг вечных 0 км).
            db.trackDao().addStats(tid, distance, points.size, points.last().time)
            // 4. Счетчики (материализованные, ЧП-5): дистанция/время — только
            // движение, площадь — только по подтвержденным (шаг 2).
            val counters = db.counterDao()
            val distCm = (distance * 100).toLong()
            val timeS = if (moving.size > 1) {
                (moving.last().time - moving.first().time) / 1000
            } else 0
            for (suffix in rangeSuffixesFor(date)) {
                if (newBase > 0) counters.addOrInsert("area_cells_$suffix", newBase.toLong())
                if (distCm > 0) counters.addOrInsert("distance_cm_$suffix", distCm)
                if (timeS > 0) counters.addOrInsert("time_s_$suffix", timeS)
            }
        }
        // Ачивки (add-achievements 2.5): после коммита транзакции — триггеры
        // по % регионов и суммарной площади; тост по событию unlocked.
        if (newBase > 0) achievements?.checkAndUnlock()
        return BatchResult(newBase, distance, resolvedTrackId)
    }

    /**
     * Ворота C (trust-v2 2.1): adjudication хвоста ожидания + открытие
     * подтвержденных (кисть + коридоры от якоря) + покрытие + компакшн.
     * Возвращает число новых базовых эквивалентов для счетчиков.
     * Все в вызывающей транзакции.
     */
    private suspend fun confirmPending(
        trackId: Long, batch: List<RawPoint>, wakeAnchor: RawPoint? = null
    ): Int {
        val tail = db.trackDao().unopenedTail(trackId)
        if (tail.isEmpty()) return 0
        val anchorEnt = db.trackDao().lastOpenedPoint(trackId)
        // Якорь пробуждения — запасной якорь коридора, пока туман дня еще
        // ничего не открыл (первый flush после выхода).
        val anchorRaw = anchorEnt?.toRaw() ?: wakeAnchor
        val newest = batch.last()
        val anchorItem = anchorEnt?.let { PendingGate.Item(-1, it.lat, it.lon, it.time) }
            ?: wakeAnchor?.let { PendingGate.Item(-1, it.lat, it.lon, it.time) }
        val res = PendingGate.adjudicate(
            pendingAsc = tail.reversed().map {
                PendingGate.Item(it.id, it.lat, it.lon, it.time, it.state)
            },
            anchor = anchorItem,
            newest = PendingGate.Item(-1, newest.lat, newest.lon, newest.time)
        )
        if (res.vetoed.isNotEmpty()) {
            // Аудит вето по причинам: возврат и протухание пишутся раздельно.
            val byReason = res.vetoed.groupBy { res.vetoedReasons[it.id] ?: VETO_RETURN }
            for ((reason, items) in byReason) {
                db.trackDao().markClosedWithReason(items.map { it.id }, reason)
            }
        }
        if (res.confirmed.isEmpty()) return 0
        // Подтвержденные — как сырые точки с открытым флагом + коридор от якоря.
        val byId = tail.associateBy { it.id }
        val confirmedRaw = res.confirmed.mapNotNull { byId[it.id]?.toRaw() }
            .map { it.copy(openFog = true) }
        // Линк якорь→первая только при порядке времени (fix-pending-gate-
        // false-vetoes D3): при инверсии (точка старше якоря) прямая линия
        // прошла бы сквозь непосещенную местность — рисуем только кисти и
        // коридоры между последовательными парами самих точек.
        val linkAnchor = linkAnchorFor(anchorRaw, confirmedRaw)
        val open = openBaseCells(confirmedRaw, linkAnchor)
        val fresh = filterCovered(open, fetchAncestors(open))
        val n = insertChunked(fresh)
        // Региональные счетчики (add-region-progress 2.1): в той же транзакции,
        // после filterCovered, по центрам новых базовых ячеек.
        if (n > 0) {
            val counters = db.counterDao()
            for ((regionId, delta) in regionIncrements(fresh)) {
                counters.addOrInsert(RegionGeometry.counterKey(regionId), delta)
            }
        }
        promoteCascade(fresh)
        db.trackDao().markOpened(res.confirmed.map { it.id })
        return n
    }

    /**
     * Разовый ремонт после ложных вето (fix-pending-gate-false-vetoes E/D5):
     * проход по трекам с `veto_return` и застрявшим `fogOpened=0`, план
     * считается чистой функцией [planRepair] (та же логика ворот C, что и
     * живой flush), применяется в транзакции на трек.
     *
     * Идемпотентность: флаг [REPAIR_FLAG] в `counters` пишется ПОСЛЕ прохода;
     * повторный запуск с флагом возвращает null и ничего не трогает. Проход
     * без пораженных строк тоже закрывается флагом (спека: не чаще раза).
     * Гонка с живым flush безвредна: план читается внутри транзакции, а если
     * flush уже разобрал хвост — план пуст.
     *
     * @return итог для DevLog или null, если ремонт уже выполнялся.
     */
    suspend fun repairVetoed(): RepairSummary? {
        if (db.counterDao().get(REPAIR_FLAG) != null) return null
        val ids = (db.trackDao().tracksWithVetoReturn() + db.trackDao().tracksWithUnopened())
            .distinct()
        var nTracks = 0
        var nPoints = 0
        var nCells = 0
        for (tid in ids) {
            var repaired = 0
            var opened = 0
            db.withTransaction {
                val plan = planRepair(db.trackDao().pointsOf(tid))
                repaired = plan.confirm.size + plan.veto.size
                if (plan.veto.isNotEmpty()) {
                    // Честные вето: та же причина той же точке = no-op (аудит не меняется).
                    for ((reason, group) in plan.veto.entries.groupBy({ it.value }, { it.key })) {
                        db.trackDao().markClosedWithReason(group, reason)
                    }
                }
                if (plan.confirm.isNotEmpty()) {
                    val confirmedRaw = plan.confirm.map { it.toRaw().copy(openFog = true) }
                    // Линк по D3 — только при порядке времени; инверсия
                    // (ремонт точек старше открытого якоря) рисует кисти
                    // и коридоры вдоль самих точек, без прямых через город.
                    val linkAnchor =
                        linkAnchorFor(db.trackDao().lastOpenedPoint(tid)?.toRaw(), confirmedRaw)
                    val open = openBaseCells(confirmedRaw, linkAnchor)
                    val fresh = filterCovered(open, fetchAncestors(open))
                    opened = insertChunked(fresh)
                    if (opened > 0) {
                        // Площадь — по дню трека (клетки посещены в тот день,
                        // а не в день ремонта). Дистанция/время не считаем:
                        // точки уже в статистике, меняется только туман.
                        val track = db.trackDao().trackById(tid)
                        val date = java.time.Instant.ofEpochMilli(track?.startedAt ?: 0L)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        val counters = db.counterDao()
                        for (suffix in rangeSuffixesFor(date)) {
                            counters.addOrInsert("area_cells_$suffix", opened.toLong())
                        }
                        for ((regionId, delta) in regionIncrements(fresh)) {
                            counters.addOrInsert(RegionGeometry.counterKey(regionId), delta)
                        }
                    }
                    promoteCascade(fresh)
                    // markReopened: вместе с вето снимается и его причина —
                    // у открытой точки не должно остаться rejectReason.
                    db.trackDao().markReopened(plan.confirm.map { it.id })
                }
            }
            if (repaired > 0) {
                nTracks++
                nPoints += repaired
                nCells += opened
            }
        }
        db.withTransaction { db.counterDao().set(CounterEntity(REPAIR_FLAG, 1)) }
        if (nCells > 0) achievements?.checkAndUnlock()
        return RepairSummary(nTracks, nPoints, nCells)
    }

    /**
     * Отбросы с причинами (tracking-reliability 3.1): материализованные счетчики
     * `rejected_<reason>_<suffix>`, те же разрезы all/день/неделя. Вызывается из
     * flush даже когда принятых точек нет — иначе день в офисе снова невидим.
     */
    suspend fun recordRejected(reasons: Map<String, Long>, date: LocalDate = LocalDate.now()) {
        if (reasons.isEmpty()) return
        db.withTransaction {
            val counters = db.counterDao()
            for ((key, n) in rejectedKeys(reasons, rangeSuffixesFor(date))) {
                counters.addOrInsert(key, n)
            }
        }
    }

    /**
     * Счётчики веток TrustEngine (tracking-reliability / fix-walk-fog-verdict 6.2):
     * все ключи BRANCH_KEYS пишутся всегда, включая 0 — ноль ветки видим
     * как кандидат на удаление правила, не как отсутствие данных.
     */
    suspend fun recordBranches(
        counts: Map<String, Long>,
        date: LocalDate = LocalDate.now()
    ) {
        db.withTransaction {
            val counters = db.counterDao()
            for ((key, n) in branchKeys(counts, rangeSuffixesFor(date))) {
                counters.addOrInsert(key, n)
            }
        }
    }

    /**
     * Эко-метрики (battery-eco 1.3): материализованные счетчики `eco_<metric>_<suffix>`
     * в тех же разрезах all/день/неделя. Пишутся из flush пачкой вместе с rejected,
     * поэтому в STANDBY-дни растут даже при 0 точек в БД.
     */
    suspend fun recordEco(metrics: Map<String, Long>, date: LocalDate = LocalDate.now()) {
        if (metrics.isEmpty()) return
        db.withTransaction {
            val counters = db.counterDao()
            for ((key, n) in ecoKeys(metrics, rangeSuffixesFor(date))) {
                counters.addOrInsert(key, n)
            }
        }
    }

    /**
     * Восстановление эко-счётчиков при импорте (fix-import-metrics 3.2):
     * снимок `counters.json` переносится как есть — эко-метрики суть свойства
     * живой сессии (каденс, GPS-время, якоря пробуждений), из прореженного
     * raw их не пересчитать. Таблица counters перед rebuild очищена, поэтому
     * значения не складываются с живыми; старый снимок без новых ключей
     * просто не даёт этих метрик («нет данных», а не нули).
     */
    suspend fun restoreEcoCounters(snapshot: Map<String, Long>) {
        val eco = ecoCountersFromSnapshot(snapshot)
        if (eco.isEmpty()) return
        db.withTransaction {
            val counters = db.counterDao()
            for ((key, value) in eco) counters.addOrInsert(key, value)
        }
    }

    suspend fun cellCount(): Long = db.fogDao().cellCount()

    /** Ячейки уровня [z] в диапазоне — чтение пирамиды для рендера. */
    suspend fun cellsInLevel(z: Int, x0: Int, x1: Int, y0: Int, y1: Int) =
        db.fogDao().cellsInZ(z, x0, x1, y0, y1)

    /** Прелоад маски: весь туман одним запросом, срез viewport — в памяти. */
    suspend fun allCells(): Set<Cell> =
        db.fogDao().allCells().mapTo(HashSet()) { Cell(it.x, it.y, it.z) }

    /** Живые инкременты маски: новые открытия без сдвига камеры. */
    fun observeCells(): kotlinx.coroutines.flow.Flow<Set<Cell>> =
        db.fogDao().observeCells().map { list ->
            list.mapTo(HashSet()) { Cell(it.x, it.y, it.z) }
        }

    // --- Пирамида: покрытие и компакшн ---

    /** Предки открываемого множества, сгруппированные по уровням (для покрытия). */
    private suspend fun fetchAncestors(open: Set<Cell>): Map<Int, Set<Cell>> {
        if (open.isEmpty()) return emptyMap()
        val byLevel = HashMap<Int, MutableSet<Cell>>()
        for (c in open) {
            for (a in FogGrid.ancestorsOf(c)) {
                byLevel.getOrPut(a.z) { HashSet() }.add(a)
            }
        }
        val out = HashMap<Int, Set<Cell>>(byLevel.size)
        for ((z, keys) in byLevel) {
            val xs = keys.map { it.x }
            val ys = keys.map { it.y }
            out[z] = db.fogDao()
                .cellsInZ(z, xs.min(), xs.max(), ys.min(), ys.max())
                .mapTo(HashSet()) { Cell(it.x, it.y, it.z) }
        }
        return out
    }

    /** Вставка чанками: в одном INSERT не больше лимита переменных SQLite. */
    private suspend fun insertChunked(fresh: Set<Cell>): Int {
        if (fresh.isEmpty()) return 0
        var n = 0
        for (chunk in fresh.toList().chunked(INSERT_CHUNK)) {
            n += db.fogDao()
                .insertCells(chunk.map { VisitedCell(it.x, it.y, it.z) })
                .count { it != -1L }
        }
        return n
    }

    /**
     * Каскад 2x2 вверх в пределах транзакции (fog-pyramid 2.2): полные четверки
     * заменяются родителем, рекурсивно. Завершается всегда (глубина ≤ 7,
     * каждый уровень сжимается); фоновый воркер не нужен — хвостов не остается,
     * т.к. батч, замкнувший четверку, тут же ее и схлопывает.
     */
    private suspend fun promoteCascade(inserted: Set<Cell>) {
        var frontier = inserted
        var level = FogGrid.BASE_Z
        var guard = 0
        while (level > FogGrid.MIN_Z && frontier.isNotEmpty() && guard < PROMOTE_GUARD) {
            guard++
            val parents = frontier.map { FogGrid.parentOf(it) }.toSet()
            val px = parents.map { it.x }
            val py = parents.map { it.y }
            val present = db.fogDao()
                .cellsInZ(level, px.min() * 2, px.max() * 2 + 1, py.min() * 2, py.max() * 2 + 1)
                .mapTo(HashSet()) { Cell(it.x, it.y, it.z) }
            val promotable = findPromotable(present, parents)
            if (promotable.isEmpty()) return
            db.fogDao().deleteCells(
                promotable.flatMap { FogGrid.childrenOf(it) }
                    .map { VisitedCell(it.x, it.y, it.z) }
            )
            val rows = db.fogDao()
                .insertCells(promotable.map { VisitedCell(it.x, it.y, it.z) })
            frontier = promotable.filterIndexed { i, _ -> rows[i] != -1L }.toSet()
            level--
        }
    }

    suspend fun clearAll() {
        db.withTransaction {
            db.fogDao().clearAll()
            db.trackDao().clearTracks()
            db.trackDao().clearPoints()
            db.counterDao().clearAll()
        }
    }

    companion object {
        /** Чанк вставки: строк × 3 колонки < лимита переменных SQLite (999). */
        const val INSERT_CHUNK = 300
        /** Страховка каскада (глубина и так ≤ 7, недостижимо на практике). */
        const val PROMOTE_GUARD = 1024
        /** Причины отбросов (tracking-reliability 3.1): единый словарь ключей. */
        const val REJECT_ACCURACY = "accuracy"
        const val REJECT_SPEED = "speed"
        const val REJECT_MOCK = "mock"
        const val REJECT_PAUSED = "paused"
        const val REJECT_NO_FIX = "no-fix"
        /** Причина jump: выброс из последовательности (gps-trust-filter 2.2). */
        const val REJECT_JUMP = "jump"
        /**
         * Причины вето ворот C (track-fix 19.09, аудит): пишутся в rejectReason
         * заветированной точки, счетчики rejected НЕ трогают (это не отброс
         * фильтра, точка в треке остается). Без миграции: колонка уже есть.
         */
        const val VETO_RETURN = "veto_return"
        const val VETO_STALE = "veto_stale"
        /**
         * Флаг-маркер разового ремонта (fix-pending-gate-false-vetoes E/D5)
         * в `counters`: пишется после успешного прохода [repairVetoed].
         */
        const val REPAIR_FLAG = "pending_gate_repair_v1"
        val REJECT_REASONS = listOf(
            REJECT_ACCURACY, REJECT_SPEED, REJECT_MOCK, REJECT_PAUSED, REJECT_NO_FIX,
            REJECT_JUMP
        )

        /** Эко-метрики (battery-eco 1.3): ключи без суффикса разреза. */
        const val ECO_FIX = "fix"
        const val ECO_STAND = "stand"
        const val ECO_GPS_MS = "gps_ms"
        const val ECO_FLUSH = "flush"
        const val ECO_PREFIX_CM = "prefix_cm"
        const val ECO_PREFIX_N = "prefix_n"
        /** Холостые BURST без подтверждения движения (wake-balance-parking 4.1). */
        const val ECO_IDLE_BURST = "idle_burst"
        /**
         * Разрыв после тишины (fix-eco-signal-loss 4.1): отдельная метрика,
         * не попадает в бюджет префикса пробуждения.
         */
        const val ECO_GAP_CM = "gap_cm"
        const val ECO_GAP_N = "gap_n"
        /**
         * Бакеты распределения префикса (fix-eco-signal-loss 4.3): медиана
         * считается по гистограмме, а не как среднее, без миграции БД.
         */
        const val ECO_PREFIX_B100_N = "prefix_b100_n"
        const val ECO_PREFIX_B200_N = "prefix_b200_n"
        const val ECO_PREFIX_B500_N = "prefix_b500_n"
        const val ECO_PREFIX_BHI_N = "prefix_bhi_n"
        val ECO_METRICS = listOf(
            ECO_FIX, ECO_STAND, ECO_GPS_MS, ECO_FLUSH, ECO_PREFIX_CM, ECO_PREFIX_N,
            ECO_IDLE_BURST, ECO_GAP_CM, ECO_GAP_N,
            ECO_PREFIX_B100_N, ECO_PREFIX_B200_N, ECO_PREFIX_B500_N, ECO_PREFIX_BHI_N
        )

        /** Разрезы счетчиков: all + день + неделя (единые для всех метрик). */
        fun rangeSuffixes(): List<String> = rangeSuffixesFor(LocalDate.now())

        /** Тот же набор разрезов для явной даты (track-debug: rebuild старых дней). */
        fun rangeSuffixesFor(date: LocalDate): List<String> {
            val day = date.toString()
            val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear()).toString() +
                "-" + date.year.toString()
            return listOf("all", "day_$day", "week_$week")
        }

        /**
         * Якорь для линка `prev` в [openBaseCells] (fix-pending-gate-false-
         * vetoes D3): только когда первая подтвержденная точка новее якоря
         * по времени. При инверсии (точка старше якоря, морозка/догоняющий
         * flush) линк дал бы прямую сквозь непосещенную местность → null.
         * Чистая функция, unit-тестируется.
         */
        fun linkAnchorFor(anchor: RawPoint?, confirmed: List<RawPoint>): RawPoint? {
            val first = confirmed.firstOrNull() ?: return null
            return anchor?.takeIf { first.time > it.time }
        }

        /**
         * План ремонта ложных вето (fix-pending-gate-false-vetoes E, D5):
         * чистая переобработка подозрительных точек трека исправленной
         * логикой ворот C. Кандидаты:
         *  (1) `fogOpened=2` с `rejectReason=veto_return` — подозрение на
         *      ложный roundTrip (морозка + инверсия времени, 49 точек 25.09);
         *  (2) `fogOpened=0` старше [PendingGate.MAX_PENDING_AGE_S] от
         *      новейшей точки трека — застрявший хвост, который живой flush
         *      уже не разберет (2 точки 19:10 25.09).
         *
         * Контекст решения — точечный, как в живом конвейере: якорь = самая
         * новая ОТКРЫТАЯ точка строго старше кандидата (включая открытые в
         * этом же проходе), `newest` = первая точка трека ПОСЛЕ кандидата
         * («куда пошла траектория», аналог `batch.last()` живого flush; конец
         * трека → сам кандидат, тогда out == back и вето геометрически
         * невозможно). Дальше — те же предикаты, что в [PendingGate]:
         * временная монотонность roundTrip (D1), протухание только не-MOVING.
         *
         * Честные вето переигрываются в ту же сторону: вылет на 4 км с
         * возвратом дает anchor=офис(старый), newest=офис(следующая) →
         * out≈4км, back≈0 → снова `veto_return`. Идемпотентно.
         * Чистая функция, unit-тестируется ([RepairPlanTest]).
         */
        fun planRepair(points: List<TrackPointEntity>): RepairPlan {
            val asc = points.sortedWith(compareBy({ it.time }, { it.id }))
            val last = asc.lastOrNull() ?: return RepairPlan(emptyList(), emptyMap())
            val candidates = asc.filter {
                (it.fogOpened == 2 && it.rejectReason == VETO_RETURN) ||
                    (it.fogOpened == 0 &&
                        (last.time - it.time) / 1000 > PendingGate.MAX_PENDING_AGE_S)
            }
            if (candidates.isEmpty()) return RepairPlan(emptyList(), emptyMap())
            val candIds = candidates.mapTo(HashSet()) { it.id }
            // Следующая точка трека после кандидата (или сам кандидат).
            val nextOf = HashMap<Long, TrackPointEntity>()
            for (i in asc.indices) {
                if (asc[i].id !in candIds) continue
                var j = i + 1
                while (j < asc.size && asc[j].time <= asc[i].time) j++
                nextOf[asc[i].id] = if (j < asc.size) asc[j] else asc[i]
            }
            // Открытые точки трека — пул якорей; подтвержденные входят в него же.
            val openedPool = ArrayList(asc.filter { it.fogOpened == 1 })
            val confirm = ArrayList<TrackPointEntity>()
            val veto = HashMap<Long, String>()
            for (c in candidates) {
                val anchor = openedPool.filter { it.time < c.time }.maxByOrNull { it.time }
                val next = nextOf[c.id] ?: c
                val out = anchor?.let { haversineM(it.lat, it.lon, c.lat, c.lon) } ?: 0.0
                val back = anchor?.let { haversineM(it.lat, it.lon, next.lat, next.lon) } ?: 0.0
                val roundTrip = anchor != null &&
                    c.time > anchor.time &&
                    out > PendingGate.VETO_MIN_DIST_M &&
                    back < TrustEngine.RETURN_RATIO * out
                val stale = c.state != TrustEngine.State.MOVING.name &&
                    (next.time - c.time) / 1000 > PendingGate.MAX_PENDING_AGE_S
                when {
                    stale -> veto[c.id] = VETO_STALE
                    roundTrip -> veto[c.id] = VETO_RETURN
                    else -> {
                        confirm.add(c)
                        openedPool.add(c)
                    }
                }
            }
            return RepairPlan(confirm, veto)
        }

        /**
         * Открываемое множество базы (чистое, без БД): кисть доверенных точек
         * + коридоры между соседними доверенными + линк к хвосту прошлого батча.
         * Unit-тестируется: непрерывность нитки и отсутствие пятен.
         */
        fun openBaseCells(points: List<RawPoint>, prev: RawPoint?): Set<Cell> {
            val open = HashSet<Cell>()
            val brushes = points.map { p ->
                if (p.openFog) FogGrid.brushRadius(p.speed, p.trust, p.acc) else null
            }
            for (i in points.indices) {
                val p = points[i]
                val r = brushes[i] ?: continue
                open += FogGrid.cellsForTrust(p.lat, p.lon, p.speed, p.trust, p.acc)
                if (i > 0) {
                    val a = points[i - 1]
                    val ra = brushes[i - 1] ?: continue
                    open += FogGrid.cellsForSegment(a.lat, a.lon, p.lat, p.lon, maxOf(ra, r))
                }
            }
            // Линк к хвосту: openFog прошлого батча в БД не храним — критерий
            // по доверию (пары статики и так рядом, нитка мизерная).
            val first = points.indices.firstOrNull { brushes[it] != null }
                ?.let { points[it] to brushes[it]!! }
            if (prev != null && prev.trust >= FogGrid.TRUST_OPEN && first != null) {
                val (f, r) = first
                open += FogGrid.cellsForSegment(prev.lat, prev.lon, f.lat, f.lon, r)
            }
            return open
        }

        /**
         * Фильтр покрытия (чистый): выкидывает базовые клетки, у которых есть
         * существующий предок любого уровня. Unit-тестируется без БД.
         */
        fun filterCovered(
            newBase: Set<Cell>,
            existingByLevel: Map<Int, Set<Cell>>
        ): Set<Cell> {
            if (existingByLevel.isEmpty()) return newBase
            return newBase.filterTo(HashSet()) { c ->
                FogGrid.ancestorsOf(c).none { a -> existingByLevel[a.z]?.contains(a) == true }
            }
        }

        /**
         * Кандидаты на promotion (чистый): родители, чья четверка детей
         * полностью присутствует. Unit-тестируется без БД.
         */
        fun findPromotable(
            levelChildren: Set<Cell>,
            candidateParents: Set<Cell>
        ): Set<Cell> {
            if (candidateParents.isEmpty()) return emptySet()
            return candidateParents.filterTo(HashSet()) { p ->
                FogGrid.childrenOf(p).all { it in levelChildren }
            }
        }

        /**
         * Чистые ключи счетчиков отбросов: тестируется без БД, гарантирует схему
         * `rejected_<reason>_<suffix>` и отсечение нулей.
         */
        fun rejectedKeys(
            reasons: Map<String, Long>,
            suffixes: List<String>
        ): List<Pair<String, Long>> {
            val out = ArrayList<Pair<String, Long>>(reasons.size * suffixes.size)
            for (s in suffixes) {
                for ((reason, n) in reasons) {
                    if (n > 0) out.add("rejected_${reason}_$s" to n)
                }
            }
            return out
        }

        /**
         * Чистые ключи счётчиков веток (tracking-reliability): схема
         * `branch_<name>_<suffix>`, ВСЕ ветки BRANCH_KEYS включая нули.
         * Тестируется без БД.
         */
        fun branchKeys(
            counts: Map<String, Long>,
            suffixes: List<String>
        ): List<Pair<String, Long>> {
            val out = ArrayList<Pair<String, Long>>(TrustEngine.BRANCH_KEYS.size * suffixes.size)
            for (s in suffixes) {
                for (name in TrustEngine.BRANCH_KEYS) {
                    out.add("branch_${name}_$s" to (counts[name] ?: 0L))
                }
            }
            return out
        }

        /**
         * Чистые ключи эко-метрик (battery-eco 1.3): схема `eco_<metric>_<suffix>`,
         * нули отсекаются как у rejected. Тестируется без БД.
         */
        fun ecoKeys(
            metrics: Map<String, Long>,
            suffixes: List<String>
        ): List<Pair<String, Long>> {
            val out = ArrayList<Pair<String, Long>>(metrics.size * suffixes.size)
            for (s in suffixes) {
                for ((metric, n) in metrics) {
                    if (n > 0) out.add("$ECO_KEY_PREFIX${metric}_$s" to n)
                }
            }
            return out
        }

        /** Префикс ключей эко-метрик в таблице counters (единый контракт). */
        const val ECO_KEY_PREFIX = "eco_"

        /**
         * Ключи снимка, восстанавливаемые при импорте (fix-import-metrics 3.2):
         * только эко-метрики; геометрия пересчитывается из raw. Чистая —
         * тестируется без БД; старый снимок без новых ключей даёт меньше метрик
         * (карточка покажет «нет данных», а не нули).
         */
        internal fun ecoCountersFromSnapshot(snapshot: Map<String, Long>): Map<String, Long> =
            snapshot.filterKeys {
                it.startsWith(ECO_KEY_PREFIX) && it.length > ECO_KEY_PREFIX.length
            }

        /**
         * План батча (fix-import-metrics 1.3): дистанция — только от хвоста
         * дня, якорь пробуждения — только коридор тумана и метрика префикса.
         * Контракт закреплён здесь, чтобы хорда якоря не вернулась в дистанцию.
         * Чистая — тестируется без БД.
         */
        data class BatchPlan(val distanceTail: RawPoint?, val corridorAnchor: RawPoint?)

        fun planBatch(dayTail: RawPoint?, wakeAnchor: RawPoint?): BatchPlan =
            BatchPlan(distanceTail = dayTail, corridorAnchor = wakeAnchor)

        /**
         * Инкременты региональных counters для новых ячеек (add-region-progress):
         * по центру ячейки — все регионы, чей полигон её содержит; вес =
         * weightOf(z) в базовых эквивалентах. Чистая — unit-тесты без БД.
         */
        fun regionIncrements(cells: Set<Cell>): Map<String, Long> {
            if (cells.isEmpty()) return emptyMap()
            val out = HashMap<String, Long>()
            for (cell in cells) {
                val (lat, lon) = FogGrid.cellCenter(cell.x, cell.y)
                val weight = FogGrid.weightOf(cell.z)
                for (region in RegionGeometry.regionsAt(lat, lon)) {
                    out.merge(region.id, weight, Long::plus)
                }
            }
            return out
        }

        /**
         * Чистая дистанция батча: только MOVING-точки с доверием (STAND
         * не накручивает км — fix-walk-fog-verdict 4.2). Отрезок от хвоста
         * прошлого батча (если он доверенный MOVING) + внутри батча между
         * доверенными соседями. STAND разрывает цепочку. Unit-тестируется.
         */
        fun batchDistance(
            points: List<RawPoint>,
            prev: RawPoint? = null
        ): Double {
            var d = 0.0
            var anchor: RawPoint? = prev?.takeIf {
                it.trust >= FogGrid.TRUST_OPEN && it.state == "MOVING"
            }
            for (p in points) {
                if (p.state != "MOVING") {
                    anchor = null
                    continue
                }
                if (p.trust >= FogGrid.TRUST_OPEN && anchor != null) {
                    d += haversineM(anchor.lat, anchor.lon, p.lat, p.lon)
                }
                anchor = if (p.trust >= FogGrid.TRUST_OPEN) p else null
            }
            return d
        }

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
