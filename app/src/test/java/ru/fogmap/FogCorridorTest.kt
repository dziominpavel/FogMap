package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.fog.FogGrid

/**
 * change recording-mode, задача 5.4: коридор рисуется только на коротких
 * отрезках. Длинный разрыв (морозка, смена сети) не должен открывать прямую
 * через непосещенную местность — только кисти по концам.
 */
class FogCorridorTest {

    private val lat = 53.9
    private val lon = 27.56

    @Test
    fun `короткий отрезок заливается коридором`() {
        val cells = FogGrid.cellsForSegment(lat, lon, lat + 0.001, lon, radiusM = 60.0)
        val mid = FogGrid.cellFor(lat + 0.0005, lon)
        assertTrue("середина отрезка не открыта", cells.contains(mid))
    }

    @Test
    fun `разрыв длиннее 500 метров не рисует нитку`() {
        val cells = FogGrid.cellsForSegment(lat, lon, lat + 0.05, lon, radiusM = 60.0)
        // 0.05° широты ≈ 5,5 км: середина обязана остаться закрытой.
        val mid = FogGrid.cellFor(lat + 0.025, lon)
        assertTrue("нитка через 5 км открылась", !cells.contains(mid))
        // Кисти по концам при этом есть.
        assertTrue(cells.contains(FogGrid.cellFor(lat, lon)))
        assertTrue(cells.contains(FogGrid.cellFor(lat + 0.05, lon)))
        assertTrue(FogGrid.CORRIDOR_LINK_MAX_M <= 500.0)
        assertEquals(500.0, FogGrid.CORRIDOR_LINK_MAX_M, 0.0)
    }
}
