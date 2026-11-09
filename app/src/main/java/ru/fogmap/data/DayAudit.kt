package ru.fogmap.data

import androidx.room.withTransaction
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackPointEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Итоги дня, пересчитанные из точек (day-ledger 2.3). */
data class DayTotals(
    val points: Int,
    val timeS: Long,
    val distanceM: Double,
    /** Точки, чья метка не принадлежит этому дню. */
    val foreign: List<TrackPointEntity>
)

/** Расхождение и результат ремонта (для DevLog и тестов). */
data class DayAuditResult(
    val day: LocalDate,
    val trackId: Long?,
    val found: Boolean,
    val totals: DayTotals?,
    val fixedPoints: Boolean = false,
    val fixedTime: Boolean = false,
    val fixedDistance: Boolean = false,
    val movedForeign: Int = 0
) {
    val ok: Boolean
        get() = found && !fixedPoints && !fixedTime && !fixedDistance && movedForeign == 0
}

/**
 * Аудит дня (day-ledger 2.3): пересчёт из точек, а не доверие инкрементам.
 *
 * Чистая часть — [recompute] — считается без БД и покрыта unit-тестами:
 * количество точек, время движения по [TrackChunking] и дистанция по тем же
 * правилам, что живой flush (MOVING и доверие не ниже порога открытия).
 */
object DayAudit {

    fun recompute(
        points: List<TrackPointEntity>,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): DayTotals {
        val asc = points.sortedWith(compareBy({ it.time }, { it.id }))
        val moving = asc.filter { it.state == "MOVING" }
        val timeS = TrackChunking.totalSpanMs(moving.map { it.time }) / 1000
        var dist = 0.0
        var prev: TrackPointEntity? = null
        for (p in moving) {
            val a = prev
            if (a != null && a.trust >= ru.fogmap.fog.FogGrid.TRUST_OPEN &&
                p.trust >= ru.fogmap.fog.FogGrid.TRUST_OPEN
            ) {
                dist += FogRepository.haversineM(a.lat, a.lon, p.lat, p.lon)
            }
            prev = p
        }
        val foreign = asc.filter {
            Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() != day
        }
        return DayTotals(points = asc.size, timeS = timeS, distanceM = dist, foreign = foreign)
    }
}

/**
 * Ремонт дня по результатам аудита (day-ledger 2.3): значения строки и
 * абсолютные счётчики перезаписываются из пересчёта, чужие точки уезжают
 * в строку своей даты.
 */
class DayAuditRepository(private val db: AppDatabase) {

    suspend fun audit(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayAuditResult {
        val entry = db.dayDao().byDay(day.toString())
            ?: return DayAuditResult(day, null, found = false, totals = null)
        val track = db.trackDao().trackById(entry.trackId)
            ?: return DayAuditResult(day, entry.trackId, found = false, totals = null)
        // Сначала убираем чужие точки: пока они в строке, любой пересчёт
        // считает их своими. Только потом меряем и правим значения дня.
        var moved = 0
        val foreign = DayAudit.recompute(db.trackDao().pointsOf(entry.trackId), day, zone).foreign
        if (foreign.isNotEmpty()) {
            db.withTransaction {
                val byDay = foreign.groupBy { p ->
                    Instant.ofEpochMilli(p.time).atZone(zone).toLocalDate()
                }
                for ((otherDay, rows) in byDay) {
                    val otherId = TrackRepository(db).openDay(otherDay)
                    db.trackDao().reassignPoints(rows.map { it.id }, otherId)
                    moved += rows.size
                }
            }
        }
        val points = db.trackDao().pointsOf(entry.trackId)
        val totals = DayAudit.recompute(points, day, zone)

        val counters = db.counterDao()
        // Только дневной разрез: «all» и «week» нельзя пересчитать из одного дня.
        val daySuffix = "day_$day"
        val distCm = (totals.distanceM * 100).toLong()
        var fixedPoints = false
        var fixedTime = false
        var fixedDistance = false

        db.withTransaction {
            if (track.pointsCount != totals.points) {
                db.trackDao().auditStats(entry.trackId, track.distanceM, totals.points, track.finishedAt)
                fixedPoints = true
            }
            if (track.distanceM != totals.distanceM) {
                db.trackDao().auditStats(entry.trackId, totals.distanceM, totals.points, track.finishedAt)
                fixedDistance = true
            }
            val timeKey = "time_s_$daySuffix"
            if (counters.get(timeKey) != totals.timeS) {
                counters.set(CounterEntity(timeKey, totals.timeS))
                fixedTime = true
            }
            val distKey = "distance_cm_$daySuffix"
            if (counters.get(distKey) != distCm) {
                counters.set(CounterEntity(distKey, distCm))
                fixedDistance = true
            }
        }
        return DayAuditResult(
            day = day, trackId = entry.trackId, found = true, totals = totals,
            fixedPoints = fixedPoints, fixedTime = fixedTime,
            fixedDistance = fixedDistance, movedForeign = moved
        )
    }
}
