package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.fog.FogGrid
import ru.fogmap.ui.screens.daySections
import ru.fogmap.ui.screens.lineSegments
import ru.fogmap.ui.screens.trustRuns
import java.time.LocalDateTime
import java.time.ZoneId

/** Чистые хелперы истории trust-v2 3.1/3.2 (без Compose и MapKit). */
class HistoryUiTest {
    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime.of(2026, 9, 18, h, m).atZone(ZoneId.systemDefault()).toInstant()
            .toEpochMilli()

    @Test
    fun `секции дня раскладывают точки по времени`() {
        val times = listOf(at(7), at(8), at(13), at(19), at(20), at(21), at(2))
        val map = daySections(times).toMap()
        assertEquals(2, map["Утро"])
        assertEquals(1, map["День"])
        assertEquals(3, map["Вечер"])
        assertEquals(1, map["Ночь"])
    }

    @Test
    fun `пустые секции не показываются`() {
        val map = daySections(listOf(at(9))).toMap()
        assertEquals(setOf("Утро"), map.keys)
    }

    @Test
    fun `прогоны клеят серии одного класса`() {
        val runs = trustRuns(
            listOf(90, 95, 80, 10, 5, 70, 85), FogGrid.TRUST_OPEN
        )
        assertEquals(
            listOf(Triple(0, 3, true), Triple(3, 5, false), Triple(5, 7, true)),
            runs
        )
    }

    @Test
    fun `одиночка приклеивается к предыдущему`() {
        val runs = trustRuns(listOf(90, 90, 10, 80, 85), FogGrid.TRUST_OPEN)
        assertEquals(
            listOf(Triple(0, 3, true), Triple(3, 5, true)),
            runs
        )
    }

    @Test
    fun `все недоверенные — один серый прогон`() {
        assertEquals(
            listOf(Triple(0, 3, false)),
            trustRuns(listOf(10, 5, 20), FogGrid.TRUST_OPEN)
        )
        assertTrue(trustRuns(emptyList(), FogGrid.TRUST_OPEN).isEmpty())
    }

    // --- fix-track-reliability-0928: разрыв доставки рвет линию ---

    @Test
    fun `дыра 12 18 - 22 30 не мостится прямой нитью`() {
        val start = at(12, 18)
        val times = listOf(
            start, start + 8_000, start + 16_000, // до разрыва
            at(22, 30), at(22, 30) + 8_000 // после разрыва
        )
        assertEquals(listOf(0..2, 3..4), lineSegments(times))
    }

    @Test
    fun `пауза до 10 минут линию не рвет, дольше — рвет`() {
        val base = at(12, 0)
        val gap5 = base + 30_000 // +30 с
        val gap10 = gap5 + 5 * 60_000L // пауза ровно 5 мин
        val same = gap10 + 10 * 60_000L // пауза ровно 10 мин — порог не превышен
        val broken = same + (10 * 60_000L + 1) // 10 мин 1 с — разрыв
        assertEquals(
            listOf(0..3, 4..4),
            lineSegments(listOf(base, gap5, gap10, same, broken))
        )
    }

    @Test
    fun `сплошная доставка — один сегмент, пусто — пусто`() {
        val base = at(9, 0)
        assertEquals(
            listOf(0..4),
            lineSegments((0..4).map { base + it * 1000L })
        )
        assertTrue(lineSegments(emptyList()).isEmpty())
    }
}
