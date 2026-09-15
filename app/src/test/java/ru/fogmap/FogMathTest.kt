package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.fog.FogGrid
import ru.fogmap.map.FogRects

class FogMathTest {
    @Test
    fun `склейка соседних клеток в один прямоугольник`() {
        val cells = setOf(
            FogGrid.Cell(0, 0), FogGrid.Cell(1, 0), FogGrid.Cell(2, 0)
        )
        val rects = FogRects.merge(cells)
        assertEquals(1, rects.size)
        assertEquals(FogRects.Rect(0, 2, 0, 0), rects[0])
    }

    @Test
    fun `разрозненные клетки не склеиваются`() {
        val cells = setOf(FogGrid.Cell(0, 0), FogGrid.Cell(5, 5))
        assertEquals(2, FogRects.merge(cells).size)
    }

    @Test
    fun `пустое множество — пустой список`() {
        assertTrue(FogRects.merge(emptySet()).isEmpty())
    }

    @Test
    fun `гаверсинус - 1 градус широты около 111 км`() {
        val d = FogRepository.haversineM(55.0, 37.0, 56.0, 37.0)
        assertTrue(d > 110_000 && d < 112_000)
    }
}
