package ru.fogmap.data

import ru.fogmap.data.db.RawFixEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.data.db.VisitedCell
import ru.fogmap.fog.FogGrid
import ru.fogmap.tracking.LocationFilter
import ru.fogmap.tracking.PendingGate
import ru.fogmap.tracking.TrustEngine
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Батч-экспорт черного ящика (track-debug 2.1): чистый билдер ZIP без
 * Android-зависимостей. Чтение БД и Share — вне, здесь только раскладка:
 * `meta.json` + `days/<дата>.jsonl` + `fog.jsonl` + `counters.json`.
 * Плюс `tracks/<дата>.jsonl` — принятые точки дня из track_points
 * (track-debug 4.3: разбор дней, записанных до появления raw_fixes).
 * Старый tolerant-парсер неизвестные entries игнорирует.
 * Формат версионируется [FORMAT_VERSION], новые поля только additive.
 */
object TrackDebugExport {
    const val FORMAT_VERSION = 1

    data class Input(
        val fromDay: String,
        val toDay: String,
        val rawByDay: Map<String, List<RawFixEntity>>,
        val cells: List<VisitedCell>,
        val counters: Map<String, Long>,
        val appVersion: String,
        /** Принятые точки по дням (день-трека по дате старта tracks.startedAt). */
        val trackByDay: Map<String, List<TrackPointEntity>> = emptyMap()
    )

    /** Дни диапазона включительно как строки yyyy-MM-dd. */
    fun daysInRange(fromDay: String, toDay: String): List<String> {
        val from = LocalDate.parse(fromDay)
        val to = LocalDate.parse(toDay)
        require(!to.isBefore(from)) { "пустой диапазон: $fromDay..$toDay" }
        val out = ArrayList<String>()
        var d = from
        while (!d.isAfter(to)) {
            out.add(d.toString())
            d = d.plusDays(1)
        }
        return out
    }

    fun buildZip(input: Input): ByteArray {
        val days = daysInRange(input.fromDay, input.toDay)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(metaJson(input).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (day in days) {
                zip.putNextEntry(ZipEntry("days/$day.jsonl"))
                val rows = input.rawByDay[day] ?: emptyList()
                for (r in rows) {
                    zip.write((rawJson(r) + "\n").toByteArray(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
            for (day in days) {
                zip.putNextEntry(ZipEntry("tracks/$day.jsonl"))
                val rows = input.trackByDay[day] ?: emptyList()
                for (r in rows) {
                    zip.write((trackJson(r) + "\n").toByteArray(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("fog.jsonl"))
            for (c in input.cells) {
                zip.write(("{\"x\":" + c.x + ",\"y\":" + c.y + ",\"z\":" + c.z + "}\n").toByteArray(Charsets.UTF_8))
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("counters.json"))
            zip.write(countersJson(input.counters).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    internal fun metaJson(input: Input): String {
        val sb = StringBuilder("{")
        sb.append("\"format\":").append(FORMAT_VERSION).append(',')
        sb.append("\"from\":").append(q(input.fromDay)).append(',')
        sb.append("\"to\":").append(q(input.toDay)).append(',')
        sb.append("\"app\":").append(q(input.appVersion)).append(',')
        sb.append("\"thresholds\":{")
        sb.append("\"maxAccuracy\":").append(LocationFilter.MAX_ACCURACY_M).append(',')
        sb.append("\"maxSpeed\":").append(LocationFilter.MAX_SPEED_MS).append(',')
        sb.append("\"teleportM\":").append(TrustEngine.TELEPORT_M).append(',')
        sb.append("\"silenceResetS\":").append(TrustEngine.SILENCE_RESET_S).append(',')
        sb.append("\"standCap\":").append(TrustEngine.STAND_CAP_MS).append(',')
        sb.append("\"pendingLag\":").append(PendingGate.LAG).append(',')
        sb.append("\"vetoMinDistM\":").append(PendingGate.VETO_MIN_DIST_M).append(',')
        sb.append("\"trustOpen\":").append(FogGrid.TRUST_OPEN).append(',')
        sb.append("\"trustHigh\":").append(FogGrid.TRUST_HIGH)
        sb.append("}}")
        return sb.toString()
    }

    internal fun rawJson(r: RawFixEntity): String {        val sb = StringBuilder("{")
        sb.append("\"time\":").append(r.time).append(',')
        sb.append("\"lat\":").append(r.lat).append(',')
        sb.append("\"lon\":").append(r.lon).append(',')
        sb.append("\"acc\":").append(r.acc).append(',')
        sb.append("\"speed\":").append(r.speed?.toString() ?: "null").append(',')
        sb.append("\"mock\":").append(r.isMock).append(',')
        sb.append("\"filter\":").append(q(r.filter)).append(',')
        sb.append("\"state\":").append(r.state?.let { q(it) } ?: "null").append(',')
        sb.append("\"trust\":").append(r.trust?.toString() ?: "null").append(',')
        sb.append("\"openFog\":").append(r.openFog?.toString() ?: "null").append(',')
        sb.append("\"reject\":").append(r.rejectReason?.let { q(it) } ?: "null").append(',')
        sb.append("\"implied\":").append(r.implied?.toString() ?: "null").append(',')
        sb.append("\"cap\":").append(r.cap?.toString() ?: "null").append(',')
        sb.append("\"teleport\":").append(r.teleport?.toString() ?: "null")
        sb.append("}")
        return sb.toString()
    }

    /** Строка принятых точек: полный вердикт для разбора серых зон без raw. */
    internal fun trackJson(r: TrackPointEntity): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(r.id).append(',')
        sb.append("\"trackId\":").append(r.trackId).append(',')
        sb.append("\"time\":").append(r.time).append(',')
        sb.append("\"lat\":").append(r.lat).append(',')
        sb.append("\"lon\":").append(r.lon).append(',')
        sb.append("\"acc\":").append(r.acc).append(',')
        sb.append("\"speed\":").append(r.speed?.toString() ?: "null").append(',')
        sb.append("\"trust\":").append(r.trust).append(',')
        sb.append("\"state\":").append(q(r.state)).append(',')
        sb.append("\"reject\":").append(r.rejectReason?.let { q(it) } ?: "null").append(',')
        sb.append("\"fogOpened\":").append(r.fogOpened)
        sb.append("}")
        return sb.toString()
    }

    internal fun countersJson(counters: Map<String, Long>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in counters.entries.sortedBy { it.key }) {
            if (!first) sb.append(',')
            first = false
            sb.append(q(k)).append(':').append(v)
        }
        sb.append("}")
        return sb.toString()
    }

    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
