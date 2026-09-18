package ru.fogmap.data

import androidx.room.withTransaction
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.CounterEntity
import ru.fogmap.data.db.TrackEntity
import ru.fogmap.fog.FogGrid
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

class TrackRepository(private val db: AppDatabase) {
    /**
     * Сутки-чанк (trust-v2 3.1): имя — дата («18 сентября»), границы
     * календарные. Старые треки-процессы лежат как есть. Коридор через
     * полночь не тянется (новый трек = нет хвоста). Счетчики — за дату старта.
     */
    suspend fun startDayChunk(date: LocalDate = LocalDate.now()): Long {
        val now = System.currentTimeMillis()
        val name = java.text.SimpleDateFormat(
            "d MMMM", java.util.Locale.forLanguageTag("ru")
        ).format(java.util.Date(now))
        val id = db.trackDao().insertTrack(
            TrackEntity(startedAt = now, finishedAt = now, distanceM = 0.0, pointsCount = 0, name = name)
        )
        db.counterDao().addOrInsert("tracks_all", 1)
        db.counterDao().addOrInsert("tracks_day_${date}", 1)
        val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear())
        db.counterDao().addOrInsert("tracks_week_${week}-${date.year}", 1)
        return id
    }

    /** Дата чанка трека (для контроля границы суток). */
    suspend fun chunkDateOf(trackId: Long): LocalDate? =
        db.trackDao().trackById(trackId)?.let {
            java.time.Instant.ofEpochMilli(it.startedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
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

data class Stats(
    val areaKm2: Double,
    val distanceM: Double,
    val tracks: Long,
    val timeS: Long,
    /** Отброшено точек за период (tracking-reliability 3.1): тишины больше нет. */
    val rejected: Long = 0
)

class StatsRepository(private val db: AppDatabase) {
    suspend fun stats(range: String): Stats {
        val c = db.counterDao()
        val cells = c.get("area_cells_$range") ?: 0
        val distCm = c.get("distance_cm_$range") ?: 0
        val tracks = c.get("tracks_$range") ?: 0
        val timeS = c.get("time_s_$range") ?: 0
        var rejected = 0L
        for (r in FogRepository.REJECT_REASONS) rejected += c.get("rejected_${r}_$range") ?: 0
        // Счетчик — в базовых эквивалентах пирамиды (вес родителя = сумме детей).
        return Stats(FogGrid.areaKm2(cells), distCm / 100.0, tracks, timeS, rejected)
    }

    /** Разбивка отбросов по причинам за период (для диагностики). */
    suspend fun rejectedBreakdown(range: String): Map<String, Long> {
        val c = db.counterDao()
        return FogRepository.REJECT_REASONS.associateWith { c.get("rejected_${it}_$range") ?: 0 }
    }

    companion object {
        fun dayRange(date: LocalDate = LocalDate.now()): String = "day_$date"
    }
}
