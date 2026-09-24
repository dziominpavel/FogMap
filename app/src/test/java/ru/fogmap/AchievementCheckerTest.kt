package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.achievement.AchievementChecker
import ru.fogmap.achievement.Achievements
import ru.fogmap.region.Regions

/** Триггеры AchievementChecker (add-achievements 5.2): все пороги. */
class AchievementCheckerTest {

    @Test
    fun `процент региона - все пороги 10-50-100`() {
        assertEquals(
            listOf("region_minsk_10"),
            AchievementChecker.candidates(
                mapOf("minsk" to 10.0), 0.0, 0, emptySet()
            )
        )
        assertEquals(
            listOf("region_minsk_10", "region_minsk_50"),
            AchievementChecker.candidates(
                mapOf("minsk" to 55.0), 0.0, 0, emptySet()
            )
        )
        assertEquals(
            listOf("region_minsk_10", "region_minsk_50", "region_minsk_100"),
            AchievementChecker.candidates(
                mapOf("minsk" to 100.0), 0.0, 0, emptySet()
            )
        )
    }

    @Test
    fun `процент ниже порога - не срабатывает`() {
        assertTrue(
            AchievementChecker.candidates(
                mapOf("minsk" to 9.9), 0.0, 0, emptySet()
            ).isEmpty()
        )
        assertTrue(
            AchievementChecker.candidates(
                mapOf("minsk" to 49.9), 0.0, 0, setOf("region_minsk_10")
            ).isEmpty()
        )
    }

    @Test
    fun `уже разблокированные не повторяются`() {
        val unlocked = setOf("region_minsk_10", "area_10", "streak_3")
        val out = AchievementChecker.candidates(
            regionPercents = mapOf("minsk" to 60.0),
            areaKm2 = 150.0,
            streakDays = 7,
            alreadyUnlocked = unlocked
        )
        assertEquals(listOf("region_minsk_50", "area_100", "streak_7"), out)
    }

    @Test
    fun `площадь - пороги 10-100-1000`() {
        assertEquals(
            listOf("area_10", "area_100", "area_1000"),
            AchievementChecker.candidates(emptyMap(), 1000.0, 0, emptySet())
        )
        assertTrue(
            AchievementChecker.candidates(emptyMap(), 9.9, 0, emptySet()).isEmpty()
        )
        assertEquals(
            listOf("area_100"),
            AchievementChecker.candidates(emptyMap(), 100.0, 0, setOf("area_10"))
        )
    }

    @Test
    fun `серия дней - пороги 3-7-30`() {
        assertEquals(
            listOf("streak_3", "streak_7"),
            AchievementChecker.candidates(emptyMap(), 0.0, 7, emptySet())
        )
        assertEquals(
            listOf("streak_30"),
            AchievementChecker.candidates(emptyMap(), 0.0, 30, setOf("streak_3", "streak_7"))
        )
        assertTrue(
            AchievementChecker.candidates(emptyMap(), 0.0, 2, emptySet()).isEmpty()
        )
    }

    @Test
    fun `nextStreak - первый день и разрыв сбрасывают в 1`() {
        assertEquals(1L, AchievementChecker.nextStreak(null, 0, 100))
        assertEquals(1L, AchievementChecker.nextStreak(90, 5, 100))
        assertEquals(1L, AchievementChecker.nextStreak(98, 5, 100))
    }

    @Test
    fun `nextStreak - соседний день продолжает серию`() {
        assertEquals(6L, AchievementChecker.nextStreak(99, 5, 100))
        assertEquals(2L, AchievementChecker.nextStreak(0, 1, 1))
    }

    @Test
    fun `nextStreak - тот же день не удваивает`() {
        assertEquals(5L, AchievementChecker.nextStreak(100, 5, 100))
        assertEquals(1L, AchievementChecker.nextStreak(100, 0, 100))
    }

    @Test
    fun `regionPercent - формула как в RegionProgress`() {
        // 1 базовая ячейка ≈ 0.000365 км²
        val cells = (10.0 / 0.000365).toLong()
        val p = AchievementChecker.regionPercent(cells, 100.0)
        assertTrue("p=$p", p > 9.9 && p < 10.1)
        assertEquals(0.0, AchievementChecker.regionPercent(0, 100.0), 1e-12)
        assertEquals(0.0, AchievementChecker.regionPercent(10, 0.0), 1e-12)
    }

    @Test
    fun `все определения - уникальные id, 13 регионов x 3 plus 3 plus 3`() {
        val all = Achievements.all()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(13 * 3 + 3 + 3, all.size)
        assertEquals(Regions.ALL.size, 13)
        assertTrue(all.all { it.title.isNotBlank() && it.condition.isNotBlank() })
    }

    @Test
    fun `regionPercent по счетчику regions - 100 percent республики`() {
        // belarus totalAreaKm2 = 207600; cells для ~100%
        val belarus = Regions.BY_ID.getValue("belarus")
        val full = (belarus.totalAreaKm2 / 0.000365).toLong()
        assertEquals(
            100.0,
            AchievementChecker.regionPercent(full, belarus.totalAreaKm2),
            0.5
        )
    }
}
