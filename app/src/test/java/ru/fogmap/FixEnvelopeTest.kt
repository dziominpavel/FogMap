package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.FixEnvelope
import java.time.ZoneId

/**
 * fix-journal 1.1/1.2: ключ и день фикса считаются один раз, при приёме.
 *
 * Полевой разбор 08.11: пачка после заморозки приходила с метками прошлых
 * суток, и день определялся моментом записи. Здесь день фиксируется меткой.
 */
class FixEnvelopeTest {

    private val minsk = ZoneId.of("Europe/Minsk")

    @Test
    fun `ключ одинаков для одного фикса и разный для разных`() {
        val a = FixEnvelope.of(1_000L, 53.9, 27.6, 5f, null, isMock = false, zone = minsk)
        val b = FixEnvelope.of(1_000L, 53.9, 27.6, 9f, 1f, isMock = false, zone = minsk)
        val c = FixEnvelope.of(1_001L, 53.9, 27.6, 5f, null, isMock = false, zone = minsk)
        assertEquals(a.key, b.key)
        assertNotEquals(a.key, c.key)
    }

    @Test
    fun `день считается в зоне устройства, а не в UTC`() {
        // 23:30 UTC 07.11 — это 02:30 08.11 в Минске.
        val t = java.time.ZonedDateTime.of(2026, 11, 7, 23, 30, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        assertEquals("2026-11-08", FixEnvelope.dayOf(t, minsk))
        assertEquals("2026-11-07", FixEnvelope.dayOf(t, ZoneId.of("UTC")))
    }

    @Test
    fun `мок помечается источником, а не теряет день`() {
        val e = FixEnvelope.of(5_000L, 53.9, 27.6, 5f, null, isMock = true, zone = minsk)
        assertEquals(FixEnvelope.SOURCE_MOCK, e.source)
        assertTrue(e.day.isNotEmpty())
    }

    @Test
    fun `сущность журнала повторяет конверт`() {
        val e = FixEnvelope.of(7_000L, 53.91, 27.61, 4f, 2.5f, isMock = false, zone = minsk)
        val row = e.toEntity()
        assertEquals(e.key, row.key)
        assertEquals(e.day, row.day)
        assertEquals(0, row.state)
        assertEquals(7_000L, row.fixTime)
    }
}
