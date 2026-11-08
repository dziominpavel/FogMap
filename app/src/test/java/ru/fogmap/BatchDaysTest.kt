package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * fix-stale-batch-day: пачка после заморозки режется по суткам самой точки.
 *
 * Полевой разбор 08.11: в 01:39 сервис разморозился и слил 90 точек, из них
 * 89 — вечер 07.11 (17:00–20:10). Раньше весь батч уходил в строку нового
 * дня: «8 ноября» начинался с вчерашнего вечера, а вечер 07.11 оставался
 * дырой. Группировка — чистый контракт [FogRepository.groupByDay].
 */
class BatchDaysTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()

    private fun p(time: Long, lat: Double = 53.9443) =
        RawPoint(time, lat, 27.6963, 5f, null)

    private fun days(groups: List<Pair<LocalDate, List<RawPoint>>>): List<LocalDate> =
        groups.map { it.first }

    @Test
    fun `пустой батч — пустой список`() {
        assertTrue(FogRepository.groupByDay(emptyList(), zone).isEmpty())
    }

    @Test
    fun `один день — одна группа в порядке прихода`() {
        val times = listOf(at(8, 13, 27), at(8, 13, 37), at(8, 14, 2))
        val groups = FogRepository.groupByDay(times.map { p(it) }, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 8)), days(groups))
        assertEquals(times, groups.single().second.map { it.time })
    }

    /**
     * Боевой расклад 08.11: 89 точек вечера 07.11 пришли после полуночи
     * вместе с первой точкой 08.11 — заморозка кончилась в 01:39.
     */
    @Test
    fun `пачка после полуночи режется на два дня и идет от старых суток`() {
        val eve = listOf(at(7, 17, 0), at(7, 20, 10))
        val night = listOf(at(8, 1, 39), at(8, 1, 40))
        val groups = FogRepository.groupByDay((eve + night).map { p(it) }, zone)

        assertEquals(
            listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)),
            days(groups)
        )
        assertEquals(eve, groups[0].second.map { it.time })
        assertEquals(night, groups[1].second.map { it.time })
    }

    @Test
    fun `группы не зависят от порядка прихода внутри батча`() {
        // Инверсия времени после разморозки: новая точка пришла раньше старой.
        val groups = FogRepository.groupByDay(
            listOf(p(at(8, 0, 5)), p(at(7, 23, 55))), zone
        )
        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 11, 7), groups[0].first)
        assertEquals(LocalDate.of(2026, 11, 8), groups[1].first)
    }

    @Test
    fun `дата считается в часовом поясе устройства`() {
        // 23:30 UTC — это уже 02:30 следующих суток в Минске (UTC+3).
        val t = ZonedDateTime.of(2026, 11, 7, 23, 30, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val mine = FogRepository.groupByDay(listOf(p(t)), zone).single().first
        val utc = FogRepository.groupByDay(listOf(p(t)), ZoneId.of("UTC")).single().first
        assertEquals(LocalDate.of(2026, 11, 8), mine)
        assertEquals(LocalDate.of(2026, 11, 7), utc)
    }
}
