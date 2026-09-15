package ru.fogmap.data

import androidx.room.withTransaction
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.data.db.VisitedCell
import ru.fogmap.fog.FogGrid
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
    val speed: Float?
)

/**
 * Транзакция «батч точек → ячейки → счетчики» (задача 2.3).
 * Атомарность «точка → туман → статистика»: всё в одной Room-транзакции.
 */
class FogRepository(private val db: AppDatabase) {

    data class BatchResult(val newCells: Int, val distanceM: Double)

    suspend fun appendPoints(trackId: Long, points: List<RawPoint>): BatchResult {
        if (points.isEmpty()) return BatchResult(0, 0.0)
        var newCells = 0
        var distance = 0.0
        db.withTransaction {
            // 1. Точки в БД пачкой.
            db.trackDao().insertPoints(points.map {
                TrackPointEntity(
                    trackId = trackId, time = it.time, lat = it.lat,
                    lon = it.lon, acc = it.acc, speed = it.speed
                )
            })
            // 2. Ячейки: круг по скорости вокруг каждой точки.
            val cells = HashSet<FogGrid.Cell>()
            for (p in points) cells += FogGrid.cellsAround(p.lat, p.lon, p.speed)
            val rows = db.fogDao().insertCells(cells.map { VisitedCell(it.x, it.y) })
            newCells = rows.count { it != -1L }
            // 3. Дистанция по гаверсинусу внутри батча.
            for (i in 1 until points.size) {
                distance += haversineM(
                    points[i - 1].lat, points[i - 1].lon,
                    points[i].lat, points[i].lon
                )
            }
            // 4. Счетчики (материализованные, ЧП-5): all + день + неделя.
            val date = LocalDate.now()
            val day = date.toString()
            val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear()).toString() +
                "-" + date.year.toString()
            val counters = db.counterDao()
            val distCm = (distance * 100).toLong()
            val timeS = if (points.size > 1) (points.last().time - points.first().time) / 1000 else 0
            for (suffix in listOf("all", "day_$day", "week_$week")) {
                if (newCells > 0) counters.addOrInsert("area_cells_$suffix", newCells.toLong())
                if (distCm > 0) counters.addOrInsert("distance_cm_$suffix", distCm)
                if (timeS > 0) counters.addOrInsert("time_s_$suffix", timeS)
            }
        }
        return BatchResult(newCells, distance)
    }

    suspend fun cellCount(): Long = db.fogDao().cellCount()

    suspend fun cellsInViewport(x0: Int, x1: Int, y0: Int, y1: Int) =
        db.fogDao().cellsIn(x0, x1, y0, y1)

    suspend fun clearAll() {
        db.withTransaction {
            db.fogDao().clearAll()
            db.trackDao().clearTracks()
            db.trackDao().clearPoints()
            db.counterDao().clearAll()
        }
    }

    companion object {
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
