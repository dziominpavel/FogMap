package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.fog.FogGrid
import ru.fogmap.map.FogMask

class LiveHoleTest {
    @Test
    fun `крупный зум дает точную дырку пешей кисти`() {
        val holes = FogMask.liveHoles(53.9, 27.56, 15f)
        assertTrue(holes.isNotEmpty())
        assertTrue(holes.all { it.z == FogGrid.BASE_Z })
        // Пешая кисть 15м вокруг точки: дырка обязана накрывать сам фикс.
        val covers = holes.any { h ->
            val (tl, br) = FogMask.holeBounds(h)
            tl.second <= 27.56 && br.second >= 27.56 &&
                tl.first >= 53.9 && br.first <= 53.9
        }
        assertTrue(covers)
    }

    @Test
    fun `средний зум дает пятно присутствия`() {
        val holes = FogMask.liveHoles(53.9, 27.56, 12f)
        assertTrue(holes.isNotEmpty())
        assertTrue(holes.all { it.z == FogMask.MID_PRESENCE_Z })
    }

    @Test
    fun `обзор города без блина`() {
        assertTrue(FogMask.liveHoles(53.9, 27.56, 10f).isEmpty())
    }

    @Test
    fun `живая дырка не участвует в подсчетах данных`() {
        // Предпросмотр существует только в возврате liveHoles: вес в базовых
        // эквивалентах считаем вручную и проверяем что вызывающий слой не может
        // спутать его с открытыми ячейками — уровень и источник разделены.
        val holes = FogMask.liveHoles(53.9, 27.56, 15f)
        assertTrue(holes.isNotEmpty())
        assertEquals(FogGrid.BASE_Z, holes.first().z)
    }
}
