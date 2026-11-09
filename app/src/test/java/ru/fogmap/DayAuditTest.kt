package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.DayAudit
import ru.fogmap.data.db.TrackPointEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * day-ledger 2.3: аудит дня пересчитывает значения из точек, а не из инкрементов.
 * Фикстуры повторяют полевые случаи 08.11: точки чужой даты, дыра доставки,
 * статика без движения.
 */
class DayAuditTest {

    private val zone = ZoneId.of("Europe/Minsk")
    private fun at(day: Int, h: Int, m: Int): Long =
        java.time.ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun p(
        id: Long, time: Long, lat: Double, lon: Double,
        state: String = "MOVING", trust: Int = 100
    ) = TrackPointEntity(
        id = id, trackId = 1, time = time, lat = lat, lon = lon,
        acc = 5f, speed = if (state == "MOVING") 3f else 0f,
        trust = trust, state = state
    )

    @Test
    fun `две движущиеся точки дают время и дистанцию`() {
        val t0 = at(8, 13, 0)
        val points = listOf(
            p(1, t0, 53.9443, 27.6963),
            p(2, t0 + 30_000, 53.9453, 27.6963) // ~111 м севернее
        )
        val totals = DayAudit.recompute(points, LocalDate.of(2026, 11, 8), zone)
        assertEquals(2, totals.points)
        assertEquals(30L, totals.timeS)
        assertTrue("дистанция ~111 м, получено ${totals.distanceM}", totals.distanceM in 100.0..125.0)
        assertTrue(totals.foreign.isEmpty())
    }

    @Test
    fun `статика не накручивает время и километры`() {
        val t0 = at(8, 9, 0)
        val points = listOf(
            p(1, t0, 53.9443, 27.6963, state = "STAND", trust = 80),
            p(2, t0 + 60_000, 53.9443, 27.6963, state = "STAND", trust = 80)
        )
        val totals = DayAudit.recompute(points, LocalDate.of(2026, 11, 8), zone)
        assertEquals(2, totals.points)
        assertEquals(0L, totals.timeS)
        assertEquals(0.0, totals.distanceM, 0.001)
    }

    @Test
    fun `дыра доставки режет время движения`() {
        val t0 = at(8, 13, 0)
        val points = listOf(
            p(1, t0, 53.9443, 27.6963),
            p(2, t0 + 20_000, 53.9444, 27.6963),
            // дыра 40 минут — в непрерывный спан не входит
            p(3, t0 + 2_420_000, 53.9445, 27.6963),
            p(4, t0 + 2_440_000, 53.9446, 27.6963)
        )
        val totals = DayAudit.recompute(points, LocalDate.of(2026, 11, 8), zone)
        assertEquals(40L, totals.timeS)
    }

    @Test
    fun `точки чужой даты попадают в foreign`() {
        val eve = at(7, 20, 10)
        val now = at(8, 1, 39)
        val points = listOf(
            p(1, eve, 53.9443, 27.6963),
            p(2, eve + 60_000, 53.9450, 27.6963),
            p(3, now, 53.9460, 27.6963)
        )
        val totals = DayAudit.recompute(points, LocalDate.of(2026, 11, 8), zone)
        assertEquals(3, totals.points)
        assertEquals(2, totals.foreign.size)
        assertTrue(totals.foreign.all { it.time < now })
    }
}
