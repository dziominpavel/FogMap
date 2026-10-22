package ru.fogmap

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.diag.DevLog
import ru.fogmap.tracking.EcoLogPayload

/**
 * fix-track-gap-visibility 4.2/4.4: новые поля лога.
 * `flush` несет фактический интервал с прошлого тика и абсолютную шкалу,
 * `day_stats` — дневные счётчики из того же источника, что counters.json.
 */
class DayStatsPayloadTest {

    @Test
    fun `flush несет since_last_ms и boot_mono_ms`() {
        val p = EcoLogPayload.flushPayload(
            batch = 2, rejected = 0, txnMs = "8.0", newCells = 5,
            mode = "eco", profile = "BURST",
            ecoFix = 10, ecoStand = 2, ecoGpsMs = 15_000,
            prefixM = 150L, prefixSrc = EcoLogPayload.PREFIX_SRC_ANCHOR,
            sinceLastMs = 1_201_000L, bootMonoMs = 26_054_145L
        )
        assertEquals(1_201_000L, p[EcoLogPayload.KEY_SINCE_LAST_MS] as Long)
        assertEquals(26_054_145L, p[EcoLogPayload.KEY_BOOT_MONO_MS] as Long)
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("\"since_last_ms\":1201000"))
        assertTrue(json.contains("\"boot_mono_ms\":26054145"))
    }

    @Test
    fun `flush без новых полей не ломает старую строку`() {
        val p = EcoLogPayload.flushPayload(
            batch = 1, rejected = 0, txnMs = "6.0", newCells = 0,
            mode = "eco", profile = "STANDBY",
            ecoFix = 1, ecoStand = 0, ecoGpsMs = 30_000
        )
        // null-поля выкидываются сериализатором: строка не пухнет.
        val json = DevLog.buildPayloadJson(p)
        assertTrue(!json.contains(EcoLogPayload.KEY_SINCE_LAST_MS))
        assertTrue(!json.contains(EcoLogPayload.KEY_BOOT_MONO_MS))
    }

    @Test
    fun `day_stats несет все дневные значения и отбросы`() {
        val p = EcoLogPayload.dayStatsPayload(
            date = "2026-10-21",
            distCm = 123_456L,
            timeS = 3_678L,
            points = 2_891,
            areaCells = 57L,
            flush = 168L,
            rejected = mapOf("no-fix" to 3L, "accuracy" to 12L),
            via = "date_change"
        )
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("\"date\":\"2026-10-21\""))
        assertTrue(json.contains("\"dist_cm\":123456"))
        assertTrue(json.contains("\"time_s\":3678"))
        assertTrue(json.contains("\"points\":2891"))
        assertTrue(json.contains("\"area_cells\":57"))
        assertTrue(json.contains("\"flush\":168"))
        assertTrue(json.contains("\"rej_no_fix\":3"))
        assertTrue(json.contains("\"rej_accuracy\":12"))
        assertTrue(json.contains("\"via\":\"date_change\""))
    }

    @Test
    fun `dayCounters читает те же ключи что counters json`() {
        val counters = mapOf(
            "distance_cm_day_2026-10-21" to 123_456L,
            "time_s_day_2026-10-21" to 3_678L,
            "area_cells_day_2026-10-21" to 57L,
            "eco_flush_day_2026-10-21" to 168L,
            "rejected_no-fix_day_2026-10-21" to 3L,
            // Чужая дата и чужие разрезы не должны попасть в срез дня.
            "distance_cm_day_2026-10-20" to 999_999L,
            "distance_cm_week_42-2026" to 777_777L
        )
        val day = FogRepository.dayCounters(counters, LocalDate.parse("2026-10-21"))
        assertEquals(123_456L, day.distCm)
        assertEquals(3_678L, day.timeS)
        assertEquals(57L, day.areaCells)
        assertEquals(168L, day.flush)
        assertEquals(3L, day.rejected[FogRepository.REJECT_NO_FIX])
        // Отсутствующие причины — нули, а не пропуски: схема стабильна.
        assertEquals(0L, day.rejected[FogRepository.REJECT_ACCURACY])
        assertEquals(FogRepository.REJECT_REASONS.size, day.rejected.size)
    }

    @Test
    fun `пустой день дает нули`() {
        val day = FogRepository.dayCounters(emptyMap(), LocalDate.parse("2026-10-20"))
        assertEquals(0L, day.distCm)
        assertEquals(0L, day.timeS)
        assertEquals(0L, day.areaCells)
        assertEquals(0L, day.flush)
        assertTrue(day.rejected.values.all { it == 0L })
    }

    @Test
    fun `no_fix несет фактическую длительность а не порог`() {
        val p = EcoLogPayload.noFixPayload(13_560_000L, "eco", "STANDBY")
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("13560000"))
    }
}
