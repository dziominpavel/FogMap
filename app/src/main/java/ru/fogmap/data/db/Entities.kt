package ru.fogmap.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

// visited_cells(x, y) — только открытые ячейки, PK (x, y).
@Entity(tableName = "visited_cells", primaryKeys = ["x", "y"])
data class VisitedCell(
    val x: Int,
    val y: Int
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
    val speed: Float?
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

    @Query("SELECT COUNT(*) FROM visited_cells")
    suspend fun cellCount(): Long

    @Query("SELECT * FROM visited_cells WHERE x BETWEEN :x0 AND :x1 AND y BETWEEN :y0 AND :y1")
    suspend fun cellsIn(x0: Int, x1: Int, y0: Int, y1: Int): List<VisitedCell>

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
