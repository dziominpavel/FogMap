package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fogmap.diag.PerfThresholds

/**
 * fix-track-gap-visibility 4.3: короткая задержка кадра (`PERF/jank`) и
 * остановка процесса (`PERF/stall`) — разные события. Раньше всё писалось как
 * `jank`/`anr_watchdog`, и остановку нельзя было отличить от лага.
 */
class PerfEventTest {

    @Test
    fun `ниже 700 мс событий нет`() {
        assertNull(PerfThresholds.eventFor(0L))
        assertNull(PerfThresholds.eventFor(PerfThresholds.JANK_MS))
    }

    @Test
    fun `задержка кадра до минуты — jank`() {
        assertEquals("jank", PerfThresholds.eventFor(PerfThresholds.JANK_MS + 1))
        assertEquals("jank", PerfThresholds.eventFor(5_000L))
        assertEquals("jank", PerfThresholds.eventFor(PerfThresholds.STALL_MS - 1))
    }

    @Test
    fun `остановка от минуты — stall`() {
        assertEquals("stall", PerfThresholds.eventFor(PerfThresholds.STALL_MS))
        assertEquals("stall", PerfThresholds.eventFor(226 * 60_000L))
    }

    @Test
    fun `пороги зафиксированы`() {
        assertEquals(700L, PerfThresholds.JANK_MS)
        assertEquals(60_000L, PerfThresholds.STALL_MS)
    }
}
