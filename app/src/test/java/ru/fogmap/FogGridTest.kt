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
        val east = FogGrid.cellFor(55.7558, 37.6195) // ~140 м восточнее
        val dx = east.x - center.x
        assertTrue(dx in 0..2)
    }

    @Test
    fun `пороги радиуса по скорости`() {
        assertEquals(50.0, FogGrid.radiusForSpeed(null), 0.0)
        assertEquals(50.0, FogGrid.radiusForSpeed(0f), 0.0)
        assertEquals(50.0, FogGrid.radiusForSpeed(2.77f), 0.0)
        assertEquals(200.0, FogGrid.radiusForSpeed(2.78f), 0.0)
        assertEquals(200.0, FogGrid.radiusForSpeed(10f), 0.0)
        assertEquals(500.0, FogGrid.radiusForSpeed(13.9f), 0.0)
        assertEquals(500.0, FogGrid.radiusForSpeed(30f), 0.0)
    }

    @Test
    fun `пеший круг уже автомобильного`() {
        val walk = FogGrid.cellsAround(55.7558, 37.6173, 1f)
        val drive = FogGrid.cellsAround(55.7558, 37.6173, 20f)
        assertTrue(walk.isNotEmpty())
        assertTrue(drive.size > walk.size)
    }

    @Test
    fun `площадь — ячейки на 0 целых 01`() {
        assertEquals(1.5, FogGrid.areaKm2(150), 1e-9)
        assertEquals(0.0, FogGrid.areaKm2(0), 0.0)
    }
}
