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
    version = 5,
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

        /**
         * v2 -> v3: доверие точки (gps-trust-filter 2.1). Старые точки считаются
         * доверенными (поведение как раньше — ворота появились позже точек).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN trust INTEGER NOT NULL DEFAULT 100")
            }
        }

        /**
         * v3 -> v4: пирамида (fog-pyramid 1.1). Сетка z17 несовместима с базой
         * z21 — вайп ячеек и счетчиков площади (тестовый прогресс, решение
         * владельца). Треки, точки, дистанция и время сохраняются.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS visited_cells")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS visited_cells " +
                        "(x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, " +
                        "PRIMARY KEY(x, y, z))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_visited_cells_z_x_y " +
                        "ON visited_cells(z, x, y)"
                )
                db.execSQL("DELETE FROM counters WHERE `key` LIKE 'area_cells_%'")
            }
        }

        /**
         * v4 -> v5: полный вердикт в точке (trust-v2 1.2). Дефолты старых строк
         * соответствуют до-лаговому поведению: state MOVING (были открыты),
         * fogOpened=1 (ячейки уже открыты). Должны совпадать с defaultValue
         * в [TrackPointEntity] — Room сверяет схему при открытии БД.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN state TEXT NOT NULL DEFAULT 'MOVING'")
                db.execSQL("ALTER TABLE track_points ADD COLUMN rejectReason TEXT")
                db.execSQL("ALTER TABLE track_points ADD COLUMN fogOpened INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
