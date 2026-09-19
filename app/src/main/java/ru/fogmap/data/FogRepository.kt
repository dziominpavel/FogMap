package ru.fogmap.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.data.db.VisitedCell
import ru.fogmap.fog.FogGrid
import ru.fogmap.fog.FogGrid.Cell
import ru.fogmap.tracking.PendingGate
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

/** Хвост прошлого батча как RawPoint для честной дистанции (trust учитывается). */
internal fun TrackPointEntity.toRaw() =
    RawPoint(time, lat, lon, acc, speed, trust, openFog = fogOpened == 1, state, rejectReason)

/**
 * Транзакция «батч точек → ячейки → счетчики» (задача 2.3).
 * Атомарность «точка → туман → статистика»: всё в одной Room-транзакции.
 */
class FogRepository(private val db: AppDatabase) {

    data class BatchResult(val newCells: Int, val distanceM: Double)

    suspend fun appendPoints(
        trackId: Long,
        points: List<RawPoint>,
        date: LocalDate = LocalDate.now()
    ): BatchResult {
        if (points.isEmpty()) return BatchResult(0, 0.0)
        var newBase = 0
        var distance = 0.0
        db.withTransaction {
            // 0. Хвост прошлого батча — для честной дистанции между flush (иначе
            // межбатчевые отрезки терялись бы, трек врал бы в меньшую сторону).
            val prev = db.trackDao().lastPoint(trackId)
            // 1. Точки в БД пачкой — сырые, с полным вердиктом (ворота разделены:
            // линия трека сохраняется всегда). fogOpened: кандидаты ворот C — 0
            // (откроются подтверждением ниже), вердиктные вето — 2 (навсегда).
            db.trackDao().insertPoints(points.map {
                TrackPointEntity(
                    trackId = trackId, time = it.time, lat = it.lat,
                    lon = it.lon, acc = it.acc, speed = it.speed, trust = it.trust,
                    state = it.state, rejectReason = it.rejectReason,
                    fogOpened = if (it.openFog) 0 else 2
                )
            })
            // 2. Ворота C: разбираем хвост ожидания (старые pending + свежий
            // батч) — подтвержденные открывают туман, заветированные закрыты.
            // Последние LAG точек всегда остаются ждать будущего.
            newBase = confirmPending(trackId, points)
            // 3. Дистанция по гаверсинусу: хвост + внутри батча, отрезки
            // с недоверенными концами пропускаются (прыжок не раздувает км).
            distance = batchDistance(points, prev?.toRaw())
            // 3б. Честная статистика трека — в той же транзакции (баг вечных 0 км).
            db.trackDao().addStats(trackId, distance, points.size, points.last().time)
            // 4. Счетчики (материализованные, ЧП-5): дистанция/время — по записи,
            // площадь — только по подтвержденным (шаг 2), в базовых эквивалентах.
            val counters = db.counterDao()
            val distCm = (distance * 100).toLong()
            val timeS = if (points.size > 1) (points.last().time - points.first().time) / 1000 else 0
            for (suffix in rangeSuffixesFor(date)) {
                if (newBase > 0) counters.addOrInsert("area_cells_$suffix", newBase.toLong())
                if (distCm > 0) counters.addOrInsert("distance_cm_$suffix", distCm)
                if (timeS > 0) counters.addOrInsert("time_s_$suffix", timeS)
            }
        }
        return BatchResult(newBase, distance)
    }

    /**
     * Ворота C (trust-v2 2.1): adjudication хвоста ожидания + открытие
     * подтвержденных (кисть + коридоры от якоря) + покрытие + компакшн.
     * Возвращает число новых базовых эквивалентов для счетчиков.
     * Все в вызывающей транзакции.
     */
    private suspend fun confirmPending(trackId: Long, batch: List<RawPoint>): Int {
        val tail = db.trackDao().unopenedTail(trackId, PendingGate.TAIL_LIMIT)
        if (tail.isEmpty()) return 0
        val anchorEnt = db.trackDao().lastOpenedPoint(trackId)
        val newest = batch.last()
        val res = PendingGate.adjudicate(
            pendingAsc = tail.reversed().map {
                PendingGate.Item(it.id, it.lat, it.lon, it.time)
            },
            anchor = anchorEnt?.let { PendingGate.Item(-1, it.lat, it.lon, it.time) },
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
        val open = openBaseCells(confirmedRaw, anchorEnt?.toRaw())
        val fresh = filterCovered(open, fetchAncestors(open))
        val n = insertChunked(fresh)
        promoteCascade(fresh)
        db.trackDao().markOpened(res.confirmed.map { it.id })
        return n
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
        val REJECT_REASONS = listOf(
            REJECT_ACCURACY, REJECT_SPEED, REJECT_MOCK, REJECT_PAUSED, REJECT_NO_FIX,
            REJECT_JUMP
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
         * Чистая дистанция батча: отрезок от хвоста прошлого батча (если он
         * доверенный) + внутри батча только между доверенными соседями.
         * Unit-тестируется без БД.
         */
        fun batchDistance(
            points: List<RawPoint>,
            prev: RawPoint? = null
        ): Double {
            var d = 0.0
            var anchor: RawPoint? = prev?.takeIf { it.trust >= FogGrid.TRUST_OPEN }
            for (p in points) {
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
