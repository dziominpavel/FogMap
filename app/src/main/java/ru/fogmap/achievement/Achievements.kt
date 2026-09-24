package ru.fogmap.achievement

import ru.fogmap.fog.FogGrid
import ru.fogmap.region.Regions

enum class AchievementCategory { REGION, AREA, STREAK }

/** Статичное определение ачивки: только unlocked хранятся в БД. */
data class AchievementDef(
    val id: String,
    val title: String,
    val condition: String,
    val category: AchievementCategory
)

/**
 * Определения ачивок (add-achievements): хардкод, locked не в БД.
 * Регионы: 10/50/100% для каждого из 13; площадь 10/100/1000 км²; серия 3/7/30.
 */
object Achievements {

    val REGION_PERCENTS = listOf(10, 50, 100)
    val AREA_KM2_THRESHOLDS = listOf(10.0, 100.0, 1000.0)
    val STREAK_DAY_THRESHOLDS = listOf(3, 7, 30)

    fun regionId(regionId: String, percent: Int): String = "region_${regionId}_$percent"
    fun areaId(km2: Double): String = "area_${km2.toLong()}"
    fun streakId(days: Int): String = "streak_$days"

    fun all(): List<AchievementDef> {
        val region = Regions.ALL.flatMap { r ->
            REGION_PERCENTS.map { p ->
                AchievementDef(
                    id = regionId(r.id, p),
                    title = "Открыл $p% «${r.name}»",
                    condition = "Достичь $p% покрытия «${r.name}»",
                    category = AchievementCategory.REGION
                )
            }
        }
        val area = AREA_KM2_THRESHOLDS.map { km2 ->
            AchievementDef(
                id = areaId(km2),
                title = "${km2.toLong()} км² открыто",
                condition = "Открыть суммарно ${km2.toLong()} км²",
                category = AchievementCategory.AREA
            )
        }
        val streak = STREAK_DAY_THRESHOLDS.map { days ->
            AchievementDef(
                id = streakId(days),
                title = "$days дней подряд",
                condition = "Трекать $days дней подряд",
                category = AchievementCategory.STREAK
            )
        }
        return region + area + streak
    }

    fun byId(): Map<String, AchievementDef> = all().associateBy { it.id }
}

/**
 * Чистые проверки триггеров (unit-тесты без БД): возвращают id для разблокировки,
 * которых ещё нет в [alreadyUnlocked].
 */
object AchievementChecker {

    /** id, которые должны быть разблокированы при данных счетчиках. */
    fun candidates(
        regionPercents: Map<String, Double>,
        areaKm2: Double,
        streakDays: Long,
        alreadyUnlocked: Set<String>
    ): List<String> {
        val out = ArrayList<String>()
        for ((regionId, percent) in regionPercents) {
            for (p in Achievements.REGION_PERCENTS) {
                if (percent >= p) {
                    val id = Achievements.regionId(regionId, p)
                    if (id !in alreadyUnlocked) out.add(id)
                }
            }
        }
        for (km2 in Achievements.AREA_KM2_THRESHOLDS) {
            if (areaKm2 >= km2) {
                val id = Achievements.areaId(km2)
                if (id !in alreadyUnlocked) out.add(id)
            }
        }
        for (days in Achievements.STREAK_DAY_THRESHOLDS) {
            if (streakDays >= days) {
                val id = Achievements.streakId(days)
                if (id !in alreadyUnlocked) out.add(id)
            }
        }
        return out
    }

    /** Процент региона из materialized counters (та же формула, что в RegionProgress). */
    fun regionPercent(regionCells: Long, totalAreaKm2: Double): Double {
        if (totalAreaKm2 <= 0.0) return 0.0
        return FogGrid.areaKm2(regionCells) / totalAreaKm2 * 100.0
    }

    /**
     * Следующая серия дней (TrackRepository.startDayChunk): разрыв > 1 дня
     * сбрасывает в 1; тот же день не удваивает; [lastDay] — epochDay.
     */
    fun nextStreak(lastDay: Long?, currentStreak: Long, today: Long): Long = when {
        lastDay == null || lastDay < today - 1 -> 1L
        lastDay == today -> currentStreak.coerceAtLeast(1L)
        lastDay == today - 1 -> currentStreak + 1
        else -> 1L
    }
}
