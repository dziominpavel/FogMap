package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.fog.FogGrid
import ru.fogmap.map.FogMask

/** Пирамида fog-pyramid 1.1/2.1: родители, веса, коридор, уровни чтения. */
class FogPyramidTest {
    @Test
    fun `родитель-дети обратимы`() {
        val c = FogGrid.cellFor(53.9, 27.56)
        val p = FogGrid.parentOf(c)
        assertEquals(20, p.z)
        assertTrue(FogGrid.childrenOf(p).contains(c))
        assertEquals(p, FogGrid.parentOf(FogGrid.childrenOf(p).first()))
    }

    @Test
    fun `родитель базы равен клетке z20 той же точки`() {
        val lat = 53.9; val lon = 27.56
        assertEquals(FogGrid.cellFor(lat, lon, 20), FogGrid.parentOf(FogGrid.cellFor(lat, lon)))
    }

    @Test
    fun `цепочка предков доходит до MIN_Z`() {
        val chain = FogGrid.ancestorsOf(FogGrid.cellFor(53.9, 27.56))
        assertEquals(FogGrid.BASE_Z - FogGrid.MIN_Z, chain.size)
        assertEquals(FogGrid.MIN_Z, chain.last().z)
    }

    @Test
    fun `веса — 4 ребенка равны родителю`() {
        assertEquals(1L, FogGrid.weightOf(21))
        assertEquals(4L, FogGrid.weightOf(20))
        assertEquals(16L, FogGrid.weightOf(19))
        assertEquals(16384L, FogGrid.weightOf(14))
        // Площадь сохраняется при компакшне.
        assertEquals(
            4 * FogGrid.areaKm2(1), FogGrid.areaKm2(FogGrid.weightOf(20)), 1e-12
        )
    }

    @Test
    fun `коридор непрерывен вдоль отрезка 110м`() {
        val lat1 = 53.9; val lon = 27.56
        val lat2 = 53.9 + 0.00099 // ~110 м севернее
        val set = FogGrid.cellsForSegment(lat1, lon, lat2, lon, 60.0)
        assertFalse(set.isEmpty())
        // Каждая проба вдоль отрезка покрыта.
        var t = 0.0
        while (t <= 1.0) {
            val cell = FogGrid.cellFor(lat1 + (lat2 - lat1) * t, lon)
            assertTrue("дырка на t=$t", set.contains(cell))
            t += 0.05
        }
    }

    @Test
    fun `коридор ограничен шириной кисти`() {
        val lat1 = 53.9; val lon = 27.56
        val lat2 = 53.9 + 0.00099
        val set = FogGrid.cellsForSegment(lat1, lon, lat2, lon, 60.0)
        // Точка в 150 м вбок от середины — вне коридора 60м.
        val mid = (lat1 + lat2) / 2.0
        val side = FogGrid.cellFor(mid, lon + 0.0023) // ~150 м восточнее
        assertFalse(set.contains(side))
    }

    @Test
    fun `длинный разрыв не тянет нитку`() {
        val set = FogGrid.cellsForSegment(53.9, 27.56, 53.909, 27.56, 60.0) // ~1000м
        // Середина отрезка не открыта — только круги по концам.
        assertFalse(set.contains(FogGrid.cellFor(53.9045, 27.56)))
        assertTrue(set.contains(FogGrid.cellFor(53.9, 27.56)))
    }

    @Test
    fun `маска вблизи точная, дальше лестница пятен`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        val cells = setOf(base)
        val near = FogMask.holesForZoom(cells, 16f)
        assertTrue(near.any { it.z == FogGrid.BASE_Z })
        val mid = FogMask.holesForZoom(cells, 12f)
        assertTrue(mid.isNotEmpty() && mid.all { it.z == FogMask.MID_PRESENCE_Z })
        val far = FogMask.holesForZoom(cells, 10f)
        assertTrue(far.isNotEmpty() && far.all { it.z == FogMask.FAR_PRESENCE_Z })
        assertEquals(
            FogGrid.ancestorAt(base, FogMask.MID_PRESENCE_Z),
            FogMask.presenceCells(cells, FogMask.MID_PRESENCE_Z).single()
        )
    }

    @Test
    fun `покрытие предком выкидывает детей`() {
        val parent = FogGrid.cellFor(53.9, 27.56, 20)
        val kids = FogGrid.childrenOf(parent).toSet()
        val existing = mapOf(20 to setOf(parent))
        assertTrue(FogRepository.filterCovered(kids, existing).isEmpty())
        // Частичное покрытие не прячет: чужой родитель не влияет.
        val stranger = FogGrid.cellFor(54.5, 28.5, 20)
        assertEquals(kids, FogRepository.filterCovered(kids, mapOf(20 to setOf(stranger))))
    }

    @Test
    fun `промоушн только полной четверки`() {
        val parent = FogGrid.cellFor(53.9, 27.56, 20)
        val kids = FogGrid.childrenOf(parent).toSet()
        assertEquals(
            setOf(parent),
            FogRepository.findPromotable(kids, setOf(parent))
        )
        assertTrue(
            FogRepository.findPromotable(kids.drop(1).toSet(), setOf(parent)).isEmpty()
        )
        assertTrue(FogRepository.findPromotable(kids, emptySet()).isEmpty())
    }

    @Test
    fun `открываемое множество — круги плюс коридор`() {
        val a = RawPoint(time = 0, lat = 53.9, lon = 27.56, acc = 8f, speed = 14f)
        val b = RawPoint(time = 8000, lat = 53.90099, lon = 27.56, acc = 8f, speed = 14f)
        val open = FogRepository.openBaseCells(listOf(a, b), null)
        // Круги по концам плюс непрерывный коридор между ними.
        assertTrue(open.size > FogGrid.cellsForTrust(a.lat, a.lon, a.speed, 100, 8f).size)
        var t = 0.0
        while (t <= 1.0) {
            val cell = FogGrid.cellFor(a.lat + (b.lat - a.lat) * t, a.lon)
            assertTrue("дырка на t=$t", open.contains(cell))
            t += 0.1
        }
    }

    @Test
    fun `недоверенные точки ничего не открывают`() {
        val a = RawPoint(time = 0, lat = 53.9, lon = 27.56, acc = 8f, speed = 14f,
            trust = 10, openFog = false)
        assertTrue(FogRepository.openBaseCells(listOf(a), null).isEmpty())
    }

    @Test
    fun `дырка не схлопывается в ноль`() {
        val tiny = FogMask.HolePx(10f, 10f, 10.5f, 10.5f)
        val fixed = FogMask.ensureMinPx(tiny)
        assertEquals(FogMask.MIN_HOLE_PX, fixed.right - fixed.left, 1e-6f)
        assertEquals(FogMask.MIN_HOLE_PX, fixed.bottom - fixed.top, 1e-6f)
        val big = FogMask.HolePx(0f, 0f, 100f, 50f)
        assertEquals(big, FogMask.ensureMinPx(big))
    }
}
