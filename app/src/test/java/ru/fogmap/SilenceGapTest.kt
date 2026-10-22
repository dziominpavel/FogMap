package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.SilenceGap

/**
 * fix-track-gap-visibility 2.1/2.2: тишина Fused считается по времени
 * последнего подтвержденного тика записи, а не по пустоте буфера.
 *
 * Объект не принимает содержимое буферов вовсе — это и есть гарантия, что
 * пачка фиксов после разморозки не маскирует дыру (21.10: 226 минут).
 */
class SilenceGapTest {

    @Test
    fun `порог тишины пять минут`() {
        assertFalse(SilenceGap.shouldReport(SilenceGap.THRESHOLD_MS - 1, false, true))
        assertTrue(SilenceGap.shouldReport(SilenceGap.THRESHOLD_MS, false, true))
    }

    @Test
    fun `пауза и отсутствие разрешений выключают счетчик`() {
        assertFalse(SilenceGap.shouldReport(60 * 60_000L, paused = true, canTrack = true))
        assertFalse(SilenceGap.shouldReport(60 * 60_000L, paused = false, canTrack = false))
    }

    /**
     * Регрессия полевой дыры 21.10: 226 минут молчания обязаны дать no_fix,
     * даже если к моменту проверки буфер полон свежими фиксами.
     */
    @Test
    fun `дыра 226 минут считается тишиной`() {
        assertTrue(SilenceGap.shouldReport(226 * 60_000L, paused = false, canTrack = true))
    }

    @Test
    fun `нормальный интервал записи не считается тишиной`() {
        // Штатный тик 15 с и задвоенный 30 с — не дыра.
        assertFalse(SilenceGap.shouldReport(15_000L, false, true))
        assertFalse(SilenceGap.shouldReport(30_000L, false, true))
        assertEquals(300_000L, SilenceGap.THRESHOLD_MS)
    }
}
