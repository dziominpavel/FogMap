package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.fog.FogGrid

/**
 * fix-pending-gate-false-vetoes D3: линк якорь→первая подтвержденная точка
 * рисуется только при порядке времени. Инверсия (морозка, догоняющий flush)
 * давала бы прямую 2–3 км сквозь непосещенную местность.
 */
class LinkAnchorTest {
    private fun rp(t: Long, lat: Double, lon: Double = 27.56) =
        RawPoint(time = t, lat = lat, lon = lon, acc = 8f, speed = 14f)

    @Test
    fun `обычный порядок — якорь старше, линк рисуется`() {
        val anchor = rp(0, 53.9)
        val first = rp(8000, 53.9027) // ~300 м севернее (< CORRIDOR_LINK_MAX_M)
        val confirmed = listOf(first, rp(16000, 53.9035))
        val link = FogRepository.linkAnchorFor(anchor, confirmed)
        assertEquals(anchor, link)
        // Коридор якорь→первая присутствует: середина сегмента открыта.
        val open = FogRepository.openBaseCells(confirmed, link)
        val mid = FogGrid.cellFor(53.90135, 27.56)
        assertTrue("нет линка к якорю", open.contains(mid))
    }

    @Test
    fun `инверсия времени — линк не рисуется, прямой через город нет`() {
        val anchor = rp(1_000_000, 53.9) // якорь НОВЕЕ подтвержденных
        val first = rp(100_000, 53.9027)
        val confirmed = listOf(first, rp(150_000, 53.9035))
        val link = FogRepository.linkAnchorFor(anchor, confirmed)
        assertNull(link)
        val open = FogRepository.openBaseCells(confirmed, link)
        // Середина прямой якорь→первая (вне кисти/коридора самих точек) закрыта.
        val mid = FogGrid.cellFor(53.90135, 27.56)
        assertTrue("прямая сквозь город открылась", !open.contains(mid))
        // Но сами точки свою кисть открыли.
        assertTrue(open.contains(FogGrid.cellFor(53.9027, 27.56)))
    }

    @Test
    fun `пустые подтвержденные и отсутствующий якорь — линка нет`() {
        assertNull(FogRepository.linkAnchorFor(rp(0, 53.9), emptyList()))
        assertNull(FogRepository.linkAnchorFor(null, listOf(rp(8000, 53.9))))
    }
}
