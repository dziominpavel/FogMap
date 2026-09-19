package ru.fogmap.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// visited_cells(x, y, z) — только открытые ячейки пирамиды, PK (x, y, z).
// Инвариант: уровни не пересекаются (родитель <=> детей нет), держит компакшн.
@Entity(
    tableName = "visited_cells",
    primaryKeys = ["x", "y", "z"],
    indices = [Index(value = ["z", "x", "y"])]
)
data class VisitedCell(
    val x: Int,
    val y: Int,
    val z: Int
)

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val distanceM: Double,
    val pointsCount: Int,
    val name: String
)

@Entity(tableName = "track_points", indices = [Index("trackId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float?,
    /** Доверие 0–100 (gps-trust-filter 2.1): воспроизводимость ворот тумана. */
    val trust: Int = 100,
    /**
     * Полный вердикт (trust-v2 1.2): state STAND/MOVING/SUSPECT для будущей
     * переобработки и отладки. Дефолт старых строк — MOVING (были открыты).
     */
    @ColumnInfo(defaultValue = "'MOVING'")
    val state: String = "MOVING",
    /** Причина отброса/подозрения (ключи REJECT_*), null = чистая точка. */
    val rejectReason: String? = null,
    /**
     * Туман открыт (ворота C): 1 = ячейки открыты, 0 = ожидание/вето.
     * Дефолт старых строк — 1 (открыты до лаговых ворот).
     */
    @ColumnInfo(defaultValue = "1")
    val fogOpened: Int = 1
)

@Entity(tableName = "counters", primaryKeys = ["key"])
data class CounterEntity(
    val key: String,
    val value: Long
)

@Dao
interface FogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCells(cells: List<VisitedCell>): List<Long>

    @Delete
    suspend fun deleteCells(cells: List<VisitedCell>)

    @Query("SELECT COUNT(*) FROM visited_cells")
    suspend fun cellCount(): Long

    @Query(
        "SELECT * FROM visited_cells WHERE z = :z " +
            "AND x BETWEEN :x0 AND :x1 AND y BETWEEN :y0 AND :y1"
    )
    suspend fun cellsInZ(z: Int, x0: Int, x1: Int, y0: Int, y1: Int): List<VisitedCell>

    /** Весь туман для прелоада маски в память (fog-mask-canvas 2.1). */
    @Query("SELECT * FROM visited_cells")
    suspend fun allCells(): List<VisitedCell>

    /** Инкременты маски: туман ползет без сдвига камеры (fog-mask-canvas 2.2). */
    @Query("SELECT * FROM visited_cells")
    fun observeCells(): Flow<List<VisitedCell>>

    @Query("DELETE FROM visited_cells")
    suspend fun clearAll()
}

@Dao
interface TrackDao {
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY time ASC")
    suspend fun pointsOf(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY time DESC, id DESC LIMIT 1")
    suspend fun lastPoint(trackId: Long): TrackPointEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun trackById(id: Long): TrackEntity?

    /**
     * День-атом (day-track-history): новейшая строка за интервал суток.
     * Нужна для find-or-create — рестарт продолжает тот же день, а не плодит
     * обломок. Старые дни-обломки лежат как есть: берем новейшую строку даты.
     */
    @Query(
        "SELECT * FROM tracks WHERE startedAt >= :fromMs AND startedAt < :toMs " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun latestInRange(fromMs: Long, toMs: Long): TrackEntity?

    /**
     * Хвост ожидания ворот C (trust-v2 2.1): последние неоткрытые точки трека.
     * Нужен после убийства процесса (догоняющее открытие) и для вето.
     */
    @Query(
        "SELECT * FROM track_points WHERE trackId = :trackId AND fogOpened = 0 " +
            "ORDER BY time DESC, id DESC LIMIT :limit"
    )
    suspend fun unopenedTail(trackId: Long, limit: Int): List<TrackPointEntity>

    @Query("UPDATE track_points SET fogOpened = 1 WHERE id IN (:ids)")
    suspend fun markOpened(ids: List<Long>)

    /** Вето ворот C: строка закрыта навсегда (линия остается, туман — нет). */
    @Query("UPDATE track_points SET fogOpened = 2 WHERE id IN (:ids)")
    suspend fun markClosed(ids: List<Long>)

    /**
     * Последняя открытая точка трека — якорь коридоров и вето (ворота C).
     * Хвосты ожидания ее не видят (fogOpened = 0), старые вердиктные — тоже (2).
     */
    @Query(
        "SELECT * FROM track_points WHERE trackId = :trackId AND fogOpened = 1 " +
            "ORDER BY time DESC, id DESC LIMIT 1"
    )
    suspend fun lastOpenedPoint(trackId: Long): TrackPointEntity?

    /**
     * Честная статистика трека (tracking-reliability 1.1): вызывается из той же
     * Room-транзакции, что и вставка батча, — «точки = статистика трека» атомарно.
     */
    @Query(
        "UPDATE tracks SET distanceM = distanceM + :distM, " +
            "pointsCount = pointsCount + :count, finishedAt = :finishedAt WHERE id = :id"
    )
    suspend fun addStats(id: Long, distM: Double, count: Int, finishedAt: Long)

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deletePoints(trackId: Long)

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM track_points")
    suspend fun clearPoints()
}

@Dao
interface CounterDao {
    @Query("SELECT value FROM counters WHERE `key` = :key")
    suspend fun get(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(counter: CounterEntity)

    @Query("UPDATE counters SET value = value + :delta WHERE `key` = :key")
    suspend fun add(key: String, delta: Long): Int

    suspend fun addOrInsert(key: String, delta: Long) {
        val updated = add(key, delta)
        if (updated == 0) set(CounterEntity(key, delta))
    }

    @Query("DELETE FROM counters")
    suspend fun clearAll()
}
