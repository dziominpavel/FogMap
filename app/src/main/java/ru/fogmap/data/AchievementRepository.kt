package ru.fogmap.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.fogmap.achievement.AchievementChecker
import ru.fogmap.achievement.AchievementDef
import ru.fogmap.achievement.Achievements
import ru.fogmap.data.db.AchievementEntity
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.region.Regions

/**
 * Разблокировка ачивок (add-achievements): читает counters, сравнивает
 * с определениями, пишет только новые строки (INSERT OR IGNORE).
 * Событие unlock — для toast/snackbar в сессии.
 */
class AchievementRepository(private val db: AppDatabase) {

    private val _unlocked = MutableSharedFlow<AchievementDef>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val unlocked: SharedFlow<AchievementDef> = _unlocked.asSharedFlow()

    suspend fun unlockedIds(): Set<String> =
        db.achievementDao().unlockedIds().toHashSet()

    suspend fun getAll(): List<AchievementEntity> = db.achievementDao().getAll()

    suspend fun isUnlocked(id: String): Boolean = db.achievementDao().isUnlocked(id)

    /**
     * Проверка всех триггеров; возвращает и эмитит только свежие разблокировки.
     * Вызывается после инкремента counters (appendPoints / startDayChunk).
     */
    suspend fun checkAndUnlock(): List<AchievementDef> {
        val counters = db.counterDao()
        val unlocked = unlockedIds()
        val regionPercents = Regions.ALL.associate { r ->
            val cells = counters.get(ru.fogmap.region.RegionGeometry.counterKey(r.id)) ?: 0L
            r.id to AchievementChecker.regionPercent(cells, r.totalAreaKm2, r.centroidLat)
        }
        val areaCells = counters.get("area_cells_all") ?: 0L
        val areaKm2 = ru.fogmap.fog.FogGrid.areaKm2(areaCells)
        val streak = counters.get(COUNTER_STREAK_DAYS) ?: 0L
        val candidates = AchievementChecker.candidates(
            regionPercents = regionPercents,
            areaKm2 = areaKm2,
            streakDays = streak,
            alreadyUnlocked = unlocked
        )
        if (candidates.isEmpty()) return emptyList()
        val defs = Achievements.byId()
        val now = System.currentTimeMillis()
        val fresh = ArrayList<AchievementDef>()
        for (id in candidates) {
            val def = defs[id] ?: continue
            val row = db.achievementDao().insert(AchievementEntity(id, now))
            if (row != -1L) {
                fresh.add(def)
                _unlocked.tryEmit(def)
            }
        }
        return fresh
    }

    /** Обновление серий дней в том же ключевом словаре counters (чистая логика — nextStreak). */
    suspend fun bumpStreak(todayEpochDay: Long) {
        val counters = db.counterDao()
        val last = counters.get(COUNTER_STREAK_LAST_DAY)
        val current = counters.get(COUNTER_STREAK_DAYS) ?: 0L
        val best = counters.get(COUNTER_STREAK_BEST) ?: 0L
        val next = AchievementChecker.nextStreak(last, current, todayEpochDay)
        counters.set(ru.fogmap.data.db.CounterEntity(COUNTER_STREAK_DAYS, next))
        counters.set(ru.fogmap.data.db.CounterEntity(COUNTER_STREAK_LAST_DAY, todayEpochDay))
        if (next > best) {
            counters.set(ru.fogmap.data.db.CounterEntity(COUNTER_STREAK_BEST, next))
        }
    }

    companion object {
        const val COUNTER_STREAK_DAYS = "streak_days"
        const val COUNTER_STREAK_LAST_DAY = "streak_last_day"
        const val COUNTER_STREAK_BEST = "streak_days_best"
    }
}
