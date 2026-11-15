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

/** Хвост прошлого батча как RawPoint для честной дистанции. */
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
        /**
         * Строки журнала приёма, разобранные этим батчем (fix-journal 3.3):
         * отметка «обработано» ставится в той же транзакции, что и точки,
         * поэтому смерть процесса между записью и отметкой не даёт дублей.
         */
        inboxIds: List<Long> = emptyList()
    ): BatchResult {
        if (points.isEmpty()) {
            if (inboxIds.isNotEmpty()) {
                db.withTransaction { db.fixInboxDao().markDone(inboxIds) }
            }
            return BatchResult(0, 0.0, trackId)
        }
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
            // 0б. Хвост дня (fix-import-metrics 1.3): дистанция считается от
            // последней записанной точки. Он же — якорь коридора между батчами,
            // но только при порядке времени ([linkAnchorFor]).
            val dayTail = db.trackDao().lastPoint(tid)?.toRaw()
            val prevOpened = linkAnchorFor(dayTail, points)
            // 1. Точки в БД пачкой — с полным вердиктом. Ворота C удалены
            // (change recording-mode, D5): принятая точка сразу считается
            // открытой, ждать подтверждения successors больше нечего.
            db.trackDao().insertPoints(points.map {
                TrackPointEntity(
                    trackId = tid, time = it.time, lat = it.lat,
                    lon = it.lon, acc = it.acc, speed = it.speed, trust = it.trust,
                    state = it.state, rejectReason = it.rejectReason,
                    fogOpened = 1
                )
            })
            // 2. Туман открывается в этом же flush: кисти + коридоры вдоль
            // точек батча (обрезка коридора > 500 м — в FogGrid).
            val openCells = openBaseCells(points, prevOpened)
            val fresh = filterCovered(openCells, fetchAncestors(openCells))
            newBase = insertChunked(fresh)
            promoteCascade(fresh)
            // 3. Дистанция/время — только MOVING (fix-walk-fog-verdict 4.2):
            // STAND-строки не накручивают км/мин.
            val moving = points.filter { it.state == "MOVING" }
            val distTail = dayTail?.takeIf { it.state == "MOVING" }
            distance = batchDistance(moving, distTail)
            // 3б. Честная статистика трека — в той же транзакции (баг вечных 0 км).
            db.trackDao().addStats(tid, distance, points.size, points.last().time)
            // 4. Счетчики (материализованные, ЧП-5): дистанция/время — только
            // движение, площадь — только по подтвержденным (шаг 2).
            val counters = db.counterDao()
            if (newBase > 0) {
                // Региональные счётчики (add-region-progress): в той же транзакции.
                for ((regionId, delta) in regionIncrements(fresh)) {
                    counters.addOrInsert(RegionGeometry.counterKey(regionId), delta)
                }
            }
            val distCm = (distance * 100).toLong()
            // Время дня (fix-track-gap-visibility 3.1): сумма непрерывных
            // отрезков, а не «последняя минус первая». Разрыв доставки в 40
            // минут больше не превращается во время движения (10.04: 18 647 с
            // при 325 м). Порог тот же, что у rebuild — [TrackChunking].
            val timeS = TrackChunking.totalSpanMs(moving.map { it.time }) / 1000
            for (suffix in rangeSuffixesFor(date)) {
                if (newBase > 0) counters.addOrInsert("area_cells_$suffix", newBase.toLong())
                if (distCm > 0) counters.addOrInsert("distance_cm_$suffix", distCm)
                if (timeS > 0) counters.addOrInsert("time_s_$suffix", timeS)
            }
            if (inboxIds.isNotEmpty()) db.fixInboxDao().markDone(inboxIds)
        }
        // Ачивки (add-achievements 2.5): после коммита транзакции — триггеры
        // по % регионов и суммарной площади; тост по событию unlocked.
        if (newBase > 0) achievements?.checkAndUnlock()
        return BatchResult(newBase, distance, resolvedTrackId)
    }

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
         * Дневной срез счётчиков для `TRACK/day_stats` (fix-track-gap-visibility
         * 4.4): ровно тот же ключ, что в `counters.json` — `*_day_<date>`.
         * Чистая: расхождений с выгрузкой нет по построению (один источник
         * схемы ключей), тестируется без БД.
         */
        data class DayCounters(
            val distCm: Long,
            val timeS: Long,
            val areaCells: Long,
            val flush: Long,
            val rejected: Map<String, Long>
        )

        fun dayCounters(all: Map<String, Long>, date: LocalDate): DayCounters {
            val day = "day_$date"
            fun get(prefix: String): Long = all["${prefix}_$day"] ?: 0L
            return DayCounters(
                distCm = get("distance_cm"),
                timeS = get("time_s"),
                areaCells = get("area_cells"),
                flush = get("$ECO_KEY_PREFIX$ECO_FLUSH"),
                rejected = REJECT_REASONS.associateWith { get("rejected_$it") }
            )
        }

        /**
         * Якорь коридора для открытия тумана (fix-pending-gate-false-vetoes D3):
         * линк якорь→первая точка рисуется только при порядке времени. При
         * инверсии (морозка, догоняющий flush, отложенная пачка) прямая прошла
         * бы сквозь непосещенную местность — линка нет, только кисти точек.
         */
        fun linkAnchorFor(anchor: RawPoint?, confirmed: List<RawPoint>): RawPoint? {
            val a = anchor ?: return null
            val first = confirmed.firstOrNull() ?: return null
            return if (first.time > a.time) a else null
        }

        fun openBaseCells(points: List<RawPoint>, prev: RawPoint?): Set<Cell> {
            val open = HashSet<Cell>()
            // Кисть не зависит от вердикта доверия (change recording-mode, D5):
            // иначе точка с вердиктом SUSPECT открывала бы ноль клеток, и
            // снятие одного лишь openFog ничего бы не дало.
            // Радиус один и для круга, и для коридора (change recording-mode):
            // иначе площадь зависела бы от того, чем точку соединили с соседней.
            val radii = points.map { FogGrid.brushRadiusBySpeed(it.speed, it.acc) }
            for (i in points.indices) {
                val p = points[i]
                val r = radii[i]
                open += FogGrid.cellsForBrush(p.lat, p.lon, p.speed, p.acc)
                if (i > 0) {
                    val a = points[i - 1]
                    open += FogGrid.cellsForSegment(a.lat, a.lon, p.lat, p.lon, maxOf(radii[i - 1], r))
                }
            }
            // Линк к хвосту дня: непрерывность нитки между батчами. Время
            // проверяет [linkAnchorFor] — при инверсии линка нет.
            if (prev != null && points.isNotEmpty()) {
                val f = points.first()
                open += FogGrid.cellsForSegment(prev.lat, prev.lon, f.lat, f.lon, radii.first())
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


        /**
         * Группировка батча по суткам ТОЧКИ, а не по «сейчас» (fix-stale-batch-day).
         *
         * Замороженный процесс отдаёт накопленную пачку разом уже после
         * полуночи, и flush писал её в строку нового дня: на 08.11 01:39 в
         * трек «8 ноября» легли 89 точек вечера 07.11 (17:00–20:10), а вечер
         * 07.11 в своей строке так и остался дырой. Точка принадлежит дню
         * своей метки — решение принимает эта чистая функция, один источник
         * для сервиса и тестов.
         *
         * Порядок групп — по возрастанию даты (писать нужно от старых суток
         * к новым), внутри группы — порядок прихода из [points]. Пусто → пусто.
         */
        fun groupByDay(
            points: List<RawPoint>,
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): List<Pair<LocalDate, List<RawPoint>>> {
            if (points.isEmpty()) return emptyList()
            val byDay = LinkedHashMap<LocalDate, MutableList<RawPoint>>()
            for (p in points) {
                val day = java.time.Instant.ofEpochMilli(p.time).atZone(zone).toLocalDate()
                byDay.getOrPut(day) { ArrayList() }.add(p)
            }
            return byDay.entries.sortedBy { it.key }.map { it.key to it.value.toList() }
        }

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
            // Доверие — диагностика, а не фильтр километража (change
            // recording-mode, D6): иначе подозрительный фикс рвал дистанцию дня
            // и живая запись расходилась с ночным аудитом.
            var anchor: RawPoint? = prev?.takeIf { it.state == "MOVING" }
            for (p in points) {
                if (p.state != "MOVING") {
                    anchor = null
                    continue
                }
                if (anchor != null) {
                    d += haversineM(anchor.lat, anchor.lon, p.lat, p.lon)
                }
                anchor = p
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
