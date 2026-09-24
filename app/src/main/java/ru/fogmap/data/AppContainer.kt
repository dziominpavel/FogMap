package ru.fogmap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import ru.fogmap.data.db.AppDatabase

private val Context.prefs: DataStore<Preferences> by preferencesDataStore(name = "fogmap")

/**
 * Ручной DI-контейнер (без Hilt/Koin) — решение ЧП-1/ЧП-8 для соло-проекта.
 * Слои data/domain/ui живут внутри пакетов фич, контейнер их связывает.
 */
class AppContainer(val context: Context) {
    val db: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "fogmap.db"
    )
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7
        )
        .build()

    val dataStore: DataStore<Preferences> = context.applicationContext.prefs

    val achievementRepository = AchievementRepository(db)
    val fogRepository = FogRepository(db, achievementRepository)
    val trackRepository = TrackRepository(db, achievementRepository)
    val statsRepository = StatsRepository(db)
    val settingsRepository = SettingsRepository(dataStore, db)
    val regionProgressRepository = RegionProgressRepository(db)
    // Achievements: тот же db, миграция 6→7 уже в addMigrations выше.
}
