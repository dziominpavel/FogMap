package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.fog.FogGrid

class FogGridTest {
    @Test
    fun `одна точка — одна ячейка, детерминированно`() {
        val a = FogGrid.cellFor(55.7558, 37.6173)
        val b = FogGrid.cellFor(55.7558, 37.6173)
        assertEquals(a, b)
    }

    @Test
    fun `соседние точки в 200 м — разные ячейки`() {
        val a = FogGrid.cellFor(55.7558, 37.6173)
        // ~200 м севернее (0.0018° широты ≈ 200 м)
        val b = FogGrid.cellFor(55.7576, 37.6173)
        assertNotEquals(a, b)
    }

    @Test
    fun `граничные точки рядом дают соседние ячейки`() {
        val center = FogGrid.cellFor(55.7558, 37.6173)
        val east = FogGrid.cellFor(55.7558, 37.61755) // ~15 м восточнее
        val dx = east.x - center.x
        assertTrue(dx in 0..2)
    }

    @Test
    fun `пороги радиуса по скорости`() {
        assertEquals(15.0, FogGrid.radiusForSpeed(null), 0.0)
        assertEquals(15.0, FogGrid.radiusForSpeed(0f), 0.0)
        assertEquals(15.0, FogGrid.radiusForSpeed(2.77f), 0.0)
        assertEquals(60.0, FogGrid.radiusForSpeed(2.78f), 0.0)
        assertEquals(60.0, FogGrid.radiusForSpeed(10f), 0.0)
        assertEquals(100.0, FogGrid.radiusForSpeed(13.9f), 0.0)
        assertEquals(100.0, FogGrid.radiusForSpeed(30f), 0.0)
    }

    @Test
    fun `пеший круг уже автомобильного`() {
        val walk = FogGrid.cellsAround(55.7558, 37.6173, 1f)
        val drive = FogGrid.cellsAround(55.7558, 37.6173, 20f)
        assertTrue(walk.isNotEmpty())
        assertTrue(drive.size > walk.size)
    }

    @Test
    fun `низкое доверие — туман не открывается`() {
        assertTrue(FogGrid.cellsForTrust(55.7558, 37.6173, 20f, 10, 8f).isEmpty())
        assertEquals(null, FogGrid.brushRadius(20f, 10, 8f))
    }

    @Test
    fun `среднее доверие и плохой accuracy — минимум`() {
        // 20 м/с просит 100м, но MID и accuracy 20 режут до пеших 15м.
        assertEquals(15.0, FogGrid.brushRadius(20f, 50, 8f)!!, 0.0)
        assertEquals(15.0, FogGrid.brushRadius(20f, 90, 20f)!!, 0.0)
        val mid = FogGrid.cellsForTrust(55.7558, 37.6173, 20f, 50, 8f)
        val walk = FogGrid.cellsAround(55.7558, 37.6173, 1f)
        assertEquals(walk, mid)
    }

    @Test
    fun `высокое доверие — полная кисть по скорости`() {
        assertEquals(100.0, FogGrid.brushRadius(20f, 90, 8f)!!, 0.0)
        val full = FogGrid.cellsForTrust(55.7558, 37.6173, 20f, 90, 8f)
        assertEquals(FogGrid.cellsAround(55.7558, 37.6173, 20f), full)
    }

    @Test
    fun `площадь — поправка cos²(широты), глобальные метрики на средней широте Беларуси`() {
        assertEquals(
            150 * FogGrid.areaPerBaseCellKm2(FogGrid.BELARUS_MEAN_LAT),
            FogGrid.areaKm2(150), 1e-12
        )
        assertEquals(0.0, FogGrid.areaKm2(0), 0.0)
        // На экваторе — номинал без поправки.
        assertEquals(
            FogGrid.AREA_PER_BASE_CELL_KM2,
            FogGrid.areaPerBaseCellKm2(0.0), 1e-12
        )
        // Точная проверка cos²: на 60° ровно четверть номинала.
        assertEquals(
            0.25,
            FogGrid.areaPerBaseCellKm2(60.0) / FogGrid.AREA_PER_BASE_CELL_KM2, 1e-9
        )
        // На широте Минска площадь клетки почти в 3 раза меньше номинала (спека fog-grid).
        assertTrue(FogGrid.areaPerBaseCellKm2(53.9) < 0.4 * FogGrid.AREA_PER_BASE_CELL_KM2)
    }
}
