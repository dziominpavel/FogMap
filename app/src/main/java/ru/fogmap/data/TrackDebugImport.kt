package ru.fogmap.data

import androidx.room.withTransaction
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.RawFixEntity
import ru.fogmap.tracking.LocationFilter
import ru.fogmap.tracking.TrustEngine
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Батч-импорт черного ящика (track-debug 3.x): tolerant-парсер ZIP +
 * перезапись день-в-день + full rebuild текущим кодом.
 *
 * Правила: версия meta НИКОГДА не блокирует импорт, stored вердикты
 * игнорируются — raw всегда перепроживается текущим
 * LocationFilter/TrustEngine/PendingGate. Геометрия (точки, туман,
 * дистанция, время, отбросы, треки) пересчитывается с нуля; эко-метрики
 * дня восстанавливаются из `counters.json` выгрузки (fix-import-metrics 3.2):
 * из прореженного raw их не пересчитать (семплирование 1/6, якорей нет).
 */
object TrackDebugImport {
    data class ParsedFile(
        val days: Map<String, List<RawFixEntity>>,
        val metaFormat: Int?,
        /** Снимок counters.json (fix-import-metrics 3.1): источник эко-метрик. */
        val counters: Map<String, Long> = emptyMap()
    )

    /** Tolerant-парсер: неизвестные поля и версии принимаются, битый JSON строки пропускается. */
    fun parseZip(bytes: ByteArray): ParsedFile {
        val days = HashMap<String, MutableList<RawFixEntity>>()
        var metaFormat: Int? = null
        var counters: Map<String, Long> = emptyMap()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.getNextEntry() ?: break
                val name = entry.name
                val content = zin.readBytes().toString(Charsets.UTF_8)
                zin.closeEntry()
                when {
                    name == "meta.json" -> metaFormat = parseFormat(content)
                    name == "counters.json" -> counters = parseCounters(content)
                    name.startsWith("days/") && name.endsWith(".jsonl") -> {
                        val day = name.removePrefix("days/").removeSuffix(".jsonl")
                        val rows = days.getOrPut(day) { ArrayList() }
                        for (line in content.lines()) {
                            if (line.isBlank()) continue
                            runCatching { parseRawLine(day, line) }.getOrNull()?.let { rows.add(it) }
                        }
                    }
                    else -> Unit // fog.jsonl: rebuild идет из raw, геометрия пересчитывается
                }
            }
        }
        return ParsedFile(days, metaFormat, counters)
    }

    /**
     * Tolerant-парсер `counters.json` (fix-import-metrics 3.1): плоский объект
     * key:number, битые пары и неизвестные ключи пропускаются — старые
     * выгрузки без новых метрик импортируются как раньше.
     */
    internal fun parseCounters(content: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        val body = content.trim().removePrefix("{").removeSuffix("}")
        for (part in splitTopLevel(body)) {
            val idx = part.indexOf(':')
            if (idx < 0) continue
            val key = part.substring(0, idx).trim().removeSurrounding("\"")
            if (key.isEmpty()) continue
            val raw = part.substring(idx + 1).trim()
            val value = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: continue
            out[key] = value
        }
        return out
    }

    internal fun parseFormat(meta: String): Int? =
        Regex("\"format\"\\s*:\\s*(\\d+)").find(meta)?.groupValues?.get(1)?.toIntOrNull()

    internal fun parseRawLine(day: String, line: String): RawFixEntity {
        val map = HashMap<String, String?>()
        val body = line.trim().removePrefix("{").removeSuffix("}")
        for (part in splitTopLevel(body)) {
            val idx = part.indexOf(':')
            if (idx < 0) continue
            val key = part.substring(0, idx).trim().removeSurrounding("\"")
            val raw = part.substring(idx + 1).trim()
            map[key] = if (raw == "null") null else raw.removeSurrounding("\"")
        }
        fun dbl(k: String): Double? = map[k]?.toDoubleOrNull()
        fun flt(k: String): Float? = map[k]?.toFloatOrNull()
        fun int(k: String): Int? = map[k]?.toIntOrNull()
        return RawFixEntity(
            day = day,
            time = map["time"]?.toLongOrNull() ?: 0L,
            lat = dbl("lat") ?: 0.0,
            lon = dbl("lon") ?: 0.0,
            acc = flt("acc") ?: -1f,
            speed = flt("speed"),
            isMock = map["mock"] == "true",
            filter = map["filter"] ?: LocationFilter.Reason.OK.key,
            state = map["state"],
            trust = int("trust"),
            openFog = int("openFog"),
            rejectReason = map["reject"],
            implied = dbl("implied"),
            cap = dbl("cap"),
            teleport = int("teleport")
        )
    }

    private fun splitTopLevel(body: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"' && (i == 0 || body[i - 1] != '\\')) inQuotes = !inQuotes
            if (c == ',' && !inQuotes) {
                out.add(cur.toString())
                cur.clear()
            } else {
                cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Чистая перепрожка дня текущим кодом (тестируется без БД): фильтр заново
     * (kind-пороги), затем TrustEngine с нуля. STAND-статика дропается из
     * точек (импорт не знает эко-профиля; дистанция по MOVING и так честна).
     */
    fun reprocessDay(rows: List<RawFixEntity>): Pair<List<RawPoint>, Map<String, Long>> {
        val rej = HashMap<String, Long>()
        val out = ArrayList<RawPoint>()
        var prev: TrustEngine.PrevState? = null
        val hist = ArrayDeque<TrustEngine.HistPoint>()
        for (r in rows.sortedBy { it.time }) {
            val accOrNull = if (r.acc < 0) null else r.acc
            val kind = ru.fogmap.tracking.MotionKindClassifier.classify(r.speed, prev?.kind)
            val reason = LocationFilter.reason(
                LocationFilter.Input(accuracy = accOrNull, speed = r.speed, isMock = r.isMock),
                kind
            )
            val softAccuracy = reason == LocationFilter.Reason.BAD_ACCURACY &&
                r.acc > 0f && accOrNull != null &&
                accOrNull > LocationFilter.maxAccuracyFor(kind)
            val hard = reason == LocationFilter.Reason.MOCK ||
                reason == LocationFilter.Reason.NO_ACCURACY ||
                reason == LocationFilter.Reason.BAD_SPEED ||
                (reason == LocationFilter.Reason.BAD_ACCURACY && !softAccuracy)
            if (hard) {
                rej[reason.key] = (rej[reason.key] ?: 0) + 1
                continue
            }
            val hp = TrustEngine.HistPoint(
                time = r.time, lat = r.lat, lon = r.lon, acc = r.acc, speed = r.speed
            )
            val v = TrustEngine.evaluate(prev, hist.toList(), hp)
            prev = v.next
            if (softAccuracy) {
                // Мягкий путь (3.2): история обновляется, точка не пишется.
                rej[reason.key] = (rej[reason.key] ?: 0) + 1
                if (v.resetHistory) {
                    val keep = hist.lastOrNull()
                    hist.clear()
                    if (keep != null) hist.addLast(keep)
                }
                hist.addLast(hp)
                TrustEngine.pruneHistory(hist, hp.time)
                continue
            }
            v.countReject?.let { rej[it] = (rej[it] ?: 0) + 1 }
            // Зеркало сервиса: сброс окна с якорем непрерывности.
            if (v.resetHistory) {
                val keep = hist.lastOrNull()
                hist.clear()
                if (keep != null) hist.addLast(keep)
            }
            hist.addLast(hp)
            TrustEngine.pruneHistory(hist, hp.time)
            if (v.state == TrustEngine.State.STAND) continue
            out.add(
                RawPoint(
                    time = r.time, lat = r.lat, lon = r.lon, acc = r.acc,
                    speed = r.speed, trust = v.trust, openFog = v.openFog,
                    state = v.state.name, rejectReason = v.countReject
                )
            )
        }
        return out to rej
    }

    /**
     * Импорт с перезаписью день-в-день и full rebuild (3.1/3.2): дни из файла
     * заменяют raw, затем ВЕСЬ raw перепроживается хронологически. Дублей
     * day-atom не остается, лишние пятна исчезают, счетчики считаются с нуля.
     *
     * Принятые точки дня подаются чанками [REBUILD_CHUNK], а не одним батчем:
     * чанк ≈ живой flush — так ворота C видят хвост по частям, как в бою
     * (окно хвоста без лимита, fix-pending-gate-false-vetoes D2), и память
     * на 700+ точек не раздувается одним куском.
     * Границы дня берутся из данных, иначе startedAt=now даст 0 мин.
     */
    suspend fun importAndRebuild(db: AppDatabase, bytes: ByteArray) {
        val parsed = parseZip(bytes)
        db.withTransaction {
            for ((day, rows) in parsed.days) {
                db.rawFixDao().deleteDay(day)
                if (rows.isNotEmpty()) db.rawFixDao().insertAll(rows)
            }
            db.fogDao().clearAll()
            db.trackDao().clearPoints()
            db.trackDao().clearTracks()
            db.counterDao().clearAll()
            val all = db.rawFixDao().range("0000-01-01", "9999-12-31")
            val days = (all.map { it.day } + parsed.days.keys).toSortedSet()
            if (days.isEmpty()) return@withTransaction
            val trackRepo = TrackRepository(db)
            val fogRepo = FogRepository(db)
            val byDay = all.groupBy { it.day }
            for (day in days) {
                val date = LocalDate.parse(day)
                val trackId = trackRepo.openDayChunk(date)
                val (accepted, rej) = reprocessDay(byDay[day] ?: emptyList())
                if (accepted.isNotEmpty()) {
                    for (chunk in chunkAccepted(accepted)) {
                        fogRepo.appendPoints(trackId, chunk, date)
                    }
                    db.trackDao().fixDayBounds(trackId, accepted.first().time, accepted.last().time)
                }
                if (rej.isNotEmpty()) fogRepo.recordRejected(rej, date)
            }
            // Эко-метрики дня (fix-import-metrics 3.2): из raw невосстановимы
            // (семплирование 1/6, якорей нет) — переносим снимок counters.json;
            // геометрия выше уже пересчитана, таблица counters была очищена.
            fogRepo.restoreEcoCounters(parsed.counters)
        }
    }

    /**
     * Чанки rebuild (fix-import-metrics 2.1): не больше [maxCount] точек и не
     * больше [maxSpanMs] спана. Разрыв режет чанк: иначе `time_s` дня впитывает
     * тишину (21.09 чанк через дыру 09:16→12:25 вносил сразу 3 часа).
     */
    internal fun chunkAccepted(
        points: List<RawPoint>,
        maxCount: Int = REBUILD_CHUNK,
        maxSpanMs: Long = REBUILD_SPAN_MS
    ): List<List<RawPoint>> {
        val out = ArrayList<List<RawPoint>>()
        var cur = ArrayList<RawPoint>()
        for (p in points) {
            if (cur.isNotEmpty() &&
                (cur.size >= maxCount || p.time - cur.first().time > maxSpanMs)
            ) {
                out.add(cur)
                cur = ArrayList()
            }
            cur.add(p)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Чанк rebuild (track-fix 19.09): того же порядка, что живой
     * flush (FLUSH_SIZE=20), чтобы ворота C видели весь хвост.
     */
    const val REBUILD_CHUNK = 20
    /** Максимальный спан чанка rebuild (fix-import-metrics 2.1), мс. */
    const val REBUILD_SPAN_MS = 120_000L
}
