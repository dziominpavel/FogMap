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
     * Сутки-чанк день-атом (day-track-history): максимум одна строка на дату.
     * Чистая вставка (счетчики tracks_*, имя — дата). Старые треки-процессы
     * лежат как есть. Коридор через полночь не тянется (новый трек = нет
     * хвоста). Счетчики — за дату старта.
     */
    suspend fun startDayChunk(date: LocalDate = LocalDate.now()): Long {
        val (fromMs, toMs) = dayBoundsMs(date)
        val now = System.currentTimeMillis()
        // startedAt обязан лежать внутри суток даты: в проде date всегда today
        // и берется now; для чужой даты (тесты, ночной ролловер на грани) now
        // оказался бы вне границ и find-or-create его бы никогда не нашел —
        // тогда кладем полдень даты.
        val at = if (now in fromMs until toMs) now else fromMs + (toMs - fromMs) / 2
        val name = java.text.SimpleDateFormat(
            "d MMMM", java.util.Locale.forLanguageTag("ru")
        ).format(java.util.Date(at))
        val id = db.trackDao().insertTrack(
            TrackEntity(startedAt = at, finishedAt = at, distanceM = 0.0, pointsCount = 0, name = name)
        )
        db.counterDao().addOrInsert("tracks_all", 1)
        db.counterDao().addOrInsert("tracks_day_${date}", 1)
        val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear())
        db.counterDao().addOrInsert("tracks_week_${week}-${date.year}", 1)
        return id
    }

    /**
     * Идемпотентное открытие дня (day-track-history D1/D5): существующая
     * строка даты переоткрывается без нового INSERT и без инкремента
     * счетчиков; новая создается (пустой, с нулями) только при отсутствии.
     * Рестарт в тот же день продолжает ту же строку; полночь — новую.
     */
    suspend fun openDayChunk(date: LocalDate = LocalDate.now()): Long {
        val (fromMs, toMs) = dayBoundsMs(date)
        return db.withTransaction {
            val existing = db.trackDao().latestInRange(fromMs, toMs)
            if (existing != null) existing.id else startDayChunk(date)
        }
    }

    companion object {
        /** Границы суток даты в системной зоне [fromMs, toMs). */
        fun dayBoundsMs(date: LocalDate): Pair<Long, Long> {
            val zone = java.time.ZoneId.systemDefault()
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return from to to
        }
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

    /** Эко-метрики за период (battery-eco 1.3): fix/stand/gps_ms/flush/prefix. */
    suspend fun ecoBreakdown(range: String): Map<String, Long> {
        val c = db.counterDao()
        return FogRepository.ECO_METRICS.associateWith { c.get("eco_${it}_$range") ?: 0 }
    }

    /** Средняя длина спрямления префикса в метрах (prefix_cm / prefix_n). */
    suspend fun ecoPrefixAvgM(range: String): Double {
        val c = db.counterDao()
        val cm = c.get("eco_${FogRepository.ECO_PREFIX_CM}_$range") ?: 0
        val n = c.get("eco_${FogRepository.ECO_PREFIX_N}_$range") ?: 0
        if (n <= 0) return 0.0
        return cm / 100.0 / n
    }

    companion object {
        fun dayRange(date: LocalDate = LocalDate.now()): String = "day_$date"
    }
}
