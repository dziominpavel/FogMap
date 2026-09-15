package ru.fogmap.data

import androidx.room.withTransaction
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackEntity
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

class TrackRepository(private val db: AppDatabase) {
    suspend fun startTrack(name: String): Long {
        val now = System.currentTimeMillis()
        val id = db.trackDao().insertTrack(
            TrackEntity(startedAt = now, finishedAt = now, distanceM = 0.0, pointsCount = 0, name = name)
        )
        db.counterDao().addOrInsert("tracks_all", 1)
        val date = LocalDate.now()
        db.counterDao().addOrInsert("tracks_day_${date}", 1)
        val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear())
        db.counterDao().addOrInsert("tracks_week_${week}-${date.year}", 1)
        return id
    }

    suspend fun allTracks() = db.trackDao().allTracks()
    suspend fun pointsOf(trackId: Long) = db.trackDao().pointsOf(trackId)
    suspend fun rename(id: Long, name: String) = db.trackDao().rename(id, name)

    /**
     * Удаление трека НЕ закрывает ячейки и НЕ уменьшает площадь (spec fog-grid/history).
     * Удаляются только запись трека и его точки.
     */
    suspend fun deleteTrack(id: Long) {
        db.withTransaction {
            db.trackDao().deletePoints(id)
            db.trackDao().deleteTrack(id)
        }
    }
}

data class Stats(val areaKm2: Double, val distanceM: Double, val tracks: Long, val timeS: Long)

class StatsRepository(private val db: AppDatabase) {
    suspend fun stats(range: String): Stats {
        val c = db.counterDao()
        val cells = c.get("area_cells_$range") ?: 0
        val distCm = c.get("distance_cm_$range") ?: 0
        val tracks = c.get("tracks_$range") ?: 0
        val timeS = c.get("time_s_$range") ?: 0
        return Stats(cells * 0.01, distCm / 100.0, tracks, timeS)
    }
}
