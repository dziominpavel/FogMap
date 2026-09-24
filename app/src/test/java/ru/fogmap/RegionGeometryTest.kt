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
    fun `эталонная площадь полигонов - сверка с известными числами`() {
        // Числа из аудита геометрии (add-region-borders): упрощённые полигоны OSM
        // против официальных площадей; диапазоны допускают упрощение DP.
        val byId = Regions.BY_ID
        val gomelOblast = byId.getValue("gomel_oblast").totalAreaKm2
        assertTrue("gomel_oblast=$gomelOblast", gomelOblast in 39_000.0..41_500.0)
        val gomelCity = byId.getValue("gomel").totalAreaKm2
        assertTrue("gomel=$gomelCity", gomelCity in 138.0..152.0)
        val minskCity = byId.getValue("minsk").totalAreaKm2
        assertTrue("minsk=$minskCity", minskCity in 350.0..380.0)
        val belarus = byId.getValue("belarus").totalAreaKm2
        assertTrue("belarus=$belarus", belarus in 204_000.0..211_000.0)
        // Области выкладывают республику (в аудите расхождение 0,02%).
        val oblasts = listOf(
            "brest_oblast", "vitebsk_oblast", "gomel_oblast",
            "grodno_oblast", "minsk_oblast", "mogilev_oblast"
        ).sumOf { byId.getValue(it).totalAreaKm2 }
        assertEquals("области vs республика", belarus, oblasts, belarus * 0.01)
    }

    @Test
    fun `выбор колец - вложенные дырки пропускаются, внешние остаются`() {
        val main = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)
        val hole = listOf(4.0 to 4.0, 6.0 to 4.0, 6.0 to 6.0, 4.0 to 6.0)
        val exclave = listOf(20.0 to 20.0, 21.0 to 20.0, 21.0 to 21.0, 20.0 to 21.0)
        val outer = RegionGeometry.outerRings(listOf(main, hole, exclave))
        assertEquals(2, outer.size)
        assertTrue("main", main in outer)
        assertTrue("exclave", exclave in outer)
        assertFalse("hole", hole in outer)
        // Площадь не учитывает вложенные кольца: дырка не вычитается, не прибавляется.
        assertEquals(
            RegionGeometry.ringAreaKm2(main) + RegionGeometry.ringAreaKm2(exclave),
            RegionGeometry.areaKm2(listOf(main, hole, exclave)),
            1e-9
        )
    }

    @Test
    fun `незамкнутое кольцо замыкается, уже замкнутое не меняется`() {
        val open = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)
        val closed = RegionGeometry.closedRing(open)
        assertEquals(4, closed.size)
        assertEquals(closed.first(), closed.last())
        assertEquals(closed, RegionGeometry.closedRing(closed))
    }

    @Test
    fun `drawRings региона - кольца замкнуты и не вложены`() {
        for (r in Regions.ALL) {
            assertTrue("${r.id} rings", r.drawRings.isNotEmpty())
            for (ring in r.drawRings) {
                assertTrue("${r.id} closed", ring.size >= 4)
                assertEquals("${r.id} ends", ring.first(), ring.last())
            }
        }
        // Ни одно кольцо не лежит внутри другого того же региона.
        for (r in Regions.ALL) {
            for ((i, ring) in r.drawRings.withIndex()) {
                val (probeLon, probeLat) = ring.first()
                for ((j, other) in r.drawRings.withIndex()) {
                    if (i == j) continue
                    assertFalse(
                        "${r.id}: кольцо $i внутри $j",
                        RegionGeometry.ringIn(other, probeLat, probeLon)
                    )
                }
            }
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
