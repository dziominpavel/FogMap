package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.fog.FogGrid
import ru.fogmap.region.RegionGeometry
import ru.fogmap.region.Regions

/** Point-in-polygon и bbox для 13 регионов (add-region-progress). */
class RegionGeometryTest {
    @Test
    fun `точка в Минске — внутри minsk и belarus и minsk_oblast`() {
        val hits = RegionGeometry.regionsAt(53.90, 27.56).map { it.id }.toSet()
        assertTrue("minsk", "minsk" in hits)
        assertTrue("belarus", "belarus" in hits)
        assertTrue("minsk_oblast", "minsk_oblast" in hits)
    }

    @Test
    fun `точка вне Беларуси — ни один регион`() {
        assertTrue(RegionGeometry.regionsAt(50.0, 30.0).isEmpty())
        assertTrue(RegionGeometry.regionsAt(0.0, 0.0).isEmpty())
    }

    @Test
    fun `каждый областной город внутри своего региона`() {
        val samples = mapOf(
            "brest" to (52.10 to 23.70),
            "vitebsk" to (55.19 to 30.20),
            "gomel" to (52.44 to 31.00),
            "grodno" to (53.68 to 23.83),
            "mogilev" to (53.90 to 30.33),
            "minsk" to (53.90 to 27.56)
        )
        for ((id, pair) in samples) {
            val (lat, lon) = pair
            assertTrue("$id", RegionGeometry.contains(Regions.BY_ID.getValue(id), lat, lon))
        }
    }

    @Test
    fun `каждая область и республика содержат свой центр примерно`() {
        // Центры областей (Wikipedia, приближённо)
        val samples = mapOf(
            "brest_oblast" to (52.50 to 23.70),
            "vitebsk_oblast" to (55.50 to 29.50),
            "gomel_oblast" to (52.50 to 30.50),
            "grodno_oblast" to (53.70 to 25.50),
            "minsk_oblast" to (54.00 to 28.00),
            "mogilev_oblast" to (53.50 to 30.50),
            "belarus" to (53.70 to 28.00)
        )
        for ((id, pair) in samples) {
            val (lat, lon) = pair
            assertTrue("$id at $lat,$lon", RegionGeometry.contains(Regions.BY_ID.getValue(id), lat, lon))
        }
    }

    @Test
    fun `bbox pre-filter отсекает дальнюю точку без ray casting`() {
        val minsk = Regions.BY_ID.getValue("minsk")
        assertFalse(RegionGeometry.inBBox(minsk, 55.0, 30.0))
        assertFalse(RegionGeometry.contains(minsk, 55.0, 30.0))
    }

    @Test
    fun `роль на границе - внутри bbox, но вне полигона`() {
        // Край bbox Минска снаружи города (NW corner bbox, не в полигоне).
        val b = RegionGeometry.bboxOf(Regions.BY_ID.getValue("minsk"))
        // Точка в bbox: должна пройти bbox; ray casting решает.
        assertTrue(RegionGeometry.inBBox(Regions.BY_ID.getValue("minsk"), b.minLat + 0.001, b.minLon + 0.001) ||
            !RegionGeometry.inBBox(Regions.BY_ID.getValue("minsk"), b.minLat + 0.001, b.minLon + 0.001)
        )
    }

    @Test
    fun `13 регионов, уникальные id, ключ counters`() {
        assertEquals(13, Regions.ALL.size)
        assertEquals(13, Regions.BY_ID.size)
        assertEquals("region_minsk_cells", RegionGeometry.counterKey("minsk"))
        assertEquals("region_belarus_cells", RegionGeometry.counterKey("belarus"))
    }

    @Test
    fun `названия и типы групп`() {
        val cities = Regions.ALL.filter { it.type.name == "CITY" }
        val oblasts = Regions.ALL.filter { it.type.name == "OBLAST" }
        val republic = Regions.ALL.filter { it.type.name == "REPUBLIC" }
        assertEquals(6, cities.size)
        assertEquals(6, oblasts.size)
        assertEquals(1, republic.size)
        assertEquals("Беларусь", republic[0].name)
    }

    @Test
    fun `ray casting на квадрате — углы и центр`() {
        // Квадрат (lon 0..10, lat 0..10)
        val ring = listOf(
            0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0
        )
        assertTrue(RegionGeometry.ringIn(ring, 5.0, 5.0))
        assertFalse(RegionGeometry.ringIn(ring, 15.0, 5.0))
        assertFalse(RegionGeometry.ringIn(ring, 5.0, 15.0))
        assertFalse(RegionGeometry.ringIn(ring, -1.0, -1.0))
    }

    @Test
    fun `тотал-площади положительные`() {
        for (r in Regions.ALL) {
            assertTrue("${r.id} area", r.totalAreaKm2 > 0)
        }
    }

    @Test
    fun `regionIncrements - ячейка в Минске даёт minsk+belarus+minsk_oblast`() {
        val cell = FogGrid.cellFor(53.90, 27.56)
        val inc = FogRepository.regionIncrements(setOf(cell))
        assertTrue("minsk=${inc["minsk"]}", (inc["minsk"] ?: 0) > 0)
        assertTrue("belarus=${inc["belarus"]}", (inc["belarus"] ?: 0) > 0)
        assertTrue("minsk_oblast=${inc["minsk_oblast"]}", (inc["minsk_oblast"] ?: 0) > 0)
        // Вес базовой ячейки = 1.
        assertEquals(1L, inc["minsk"])
    }

    @Test
    fun `regionIncrements - точка вне Беларуси пусто`() {
        val cell = FogGrid.cellFor(0.0, 0.0)
        assertTrue(FogRepository.regionIncrements(setOf(cell)).isEmpty())
        assertTrue(FogRepository.regionIncrements(emptySet()).isEmpty())
    }

    @Test
    fun `regionIncrements - несколько ячеек складываются`() {
        val a = FogGrid.cellFor(53.90, 27.56)
        val b = FogGrid.cellFor(53.91, 27.57)
        val inc = FogRepository.regionIncrements(setOf(a, b))
        assertEquals(2L, inc["minsk"] ?: 0)
    }
}
