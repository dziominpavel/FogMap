package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.fog.FogGrid
import ru.fogmap.ui.screens.daySections
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
}
