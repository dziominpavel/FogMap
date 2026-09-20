package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.map.COARSE_CORNER_FRAC
import ru.fogmap.map.COARSE_FEATHER_MULT
import ru.fogmap.map.FogMask
import ru.fogmap.map.cornerPxFor
import ru.fogmap.map.featherPxFor

/** Мягкие грубые пятна (smooth-fog-zoom 3.1): углы и перо от размера дырки. */
class FogOverlayTest {
    @Test
    fun `точные дырки рисуются базовыми константами`() {
        assertEquals(FogMask.CORNER_PX, cornerPxFor(10f, 8f, false, FogMask.CORNER_PX), 1e-6f)
        assertEquals(FogMask.FEATHER_PX, featherPxFor(false, FogMask.FEATHER_PX), 1e-6f)
    }

    @Test
    fun `грубые пятна скругляются половиной меньшей стороны`() {
        // Пятно 360 м на среднем зуме: сотни px — угол заметно больше константы.
        val corner = cornerPxFor(400f, 300f, true, FogMask.CORNER_PX)
        assertEquals(300f * COARSE_CORNER_FRAC, corner, 1e-6f)
        // Крошечное грубое пятно не уходит ниже базового радиуса.
        assertEquals(
            FogMask.CORNER_PX, cornerPxFor(4f, 4f, true, FogMask.CORNER_PX), 1e-6f
        )
    }

    @Test
    fun `грубые пятна получают широкое перо`() {
        assertEquals(
            FogMask.FEATHER_PX * COARSE_FEATHER_MULT,
            featherPxFor(true, FogMask.FEATHER_PX), 1e-6f
        )
    }
}
