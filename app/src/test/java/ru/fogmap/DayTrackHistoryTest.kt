package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.data.TrackRepository
import ru.fogmap.ui.screens.daySections
import java.time.LocalDate
import java.time.ZoneId

/**
 * День-атом (day-track-history): чистые инварианты без Android/Room.
 * Room-идемпотентность покрыта в DatabaseTest (инструментальный).
 */
class DayTrackHistoryTest {
    @Test
    fun `границы дня содержат полдень и исключают полночь следующих суток`() {
        val date = LocalDate.of(2026, 9, 18)
        val zone = ZoneId.systemDefault()
        val (from, to) = TrackRepository.dayBoundsMs(date)
        val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val midnightNext = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(noon in from until to)
        assertEquals(midnightNext, to)
    }

    @Test
    fun `соседние дни стыкуются без щелей и нахлестов`() {
        val date = LocalDate.of(2026, 9, 18)
        val (_, toA) = TrackRepository.dayBoundsMs(date)
        val (fromB, _) = TrackRepository.dayBoundsMs(date.plusDays(1))
        assertEquals(toA, fromB)
    }

    @Test
    fun `дистанция считается только по движению — пустой батч статики дает ноль`() {
        // После дропа STAND в батче нет точек статики вообще: суммировать нечего.
        assertEquals(0.0, FogRepository.batchDistance(emptyList()), 1e-9)
    }

    @Test
    fun `батч движения дает честную дистанцию без поправки на статику`() {
        // Две движущиеся точки в 0.001° (~111 м): статики рядом нет, джиттера нет.
        val a = RawPoint(
            time = 0, lat = 55.0, lon = 37.0, acc = 10f, speed = 1f,
            trust = 100, state = "MOVING"
        )
        val b = RawPoint(
            time = 8000, lat = 55.001, lon = 37.0, acc = 10f, speed = 1f,
            trust = 100, state = "MOVING"
        )
        val d = FogRepository.batchDistance(listOf(a, b))
        assertTrue("ожидалось ~111 м, получено $d", d > 110 && d < 113)
    }

    @Test
    fun `пустой день не дает секций`() {
        assertTrue(daySections(emptyList()).isEmpty())
    }
}
