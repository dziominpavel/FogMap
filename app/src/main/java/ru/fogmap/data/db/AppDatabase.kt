package ru.fogmap.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * БД Room (ЧП-2): visited_cells + tracks + track_points + counters.
 * Миграции с версии 1, без шифрования (решение ЧП-7).
 */
@Database(
    entities = [VisitedCell::class, TrackEntity::class, TrackPointEntity::class, CounterEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fogDao(): FogDao
    abstract fun trackDao(): TrackDao
    abstract fun counterDao(): CounterDao

    companion object {
        /** v1 -> v2: индекс по track_points(trackId) для быстрого чтения трека. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_points_trackId ON track_points(trackId)")
            }
        }
    }
}
