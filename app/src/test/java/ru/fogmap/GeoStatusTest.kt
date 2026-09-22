package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.ui.screens.GeoStatus

class GeoStatusTest {

    @Test
    fun `граница гейта 100 м проходит, 100 целых 1 — нет`() {
        assertTrue(GeoStatus.plausible(100f))
        assertFalse(GeoStatus.plausible(100.1f))
    }

    @Test
    fun `без accuracy и с баговым нулём гейт не проходит`() {
        assertFalse(GeoStatus.plausible(null))
        assertFalse(GeoStatus.plausible(0f))
        assertFalse(GeoStatus.plausible(-1f))
    }

    @Test
    fun `мастер гео выключен — высший приоритет чипа`() {
        assertEquals(GeoStatus.State.GEO_OFF, GeoStatus.state(false, 1_000L, 1_001L))
        assertEquals(GeoStatus.State.GEO_OFF, GeoStatus.state(false, null, 1_001L))
    }

    @Test
    fun `правдоподобного фикса не было — поиск спутников`() {
        assertEquals(GeoStatus.State.SEARCHING, GeoStatus.state(true, null, 60_000L))
    }

    @Test
    fun `свежий фикс — точка получена вплоть до границы 120 секунд`() {
        assertEquals(GeoStatus.State.FOUND, GeoStatus.state(true, 0L, 119_000L))
        assertEquals(GeoStatus.State.FOUND, GeoStatus.state(true, 0L, 120_000L))
        // фикс свежее последнего тика чипа — разница отрицательная, всё свежо
        assertEquals(GeoStatus.State.FOUND, GeoStatus.state(true, 500_000L, 10_000L))
    }

    @Test
    fun `тишина дольше 120 секунд — протухло`() {
        assertEquals(GeoStatus.State.STALE, GeoStatus.state(true, 0L, 120_001L))
    }
}
