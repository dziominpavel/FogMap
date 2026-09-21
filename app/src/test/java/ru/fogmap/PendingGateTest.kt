package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.PendingGate

/** Ворота C (trust-v2 2.1): подтверждение лагом, вето возврата, протухание. */
class PendingGateTest {
    private fun pt(id: Long, lat: Double, t: Long, lon: Double = 27.0, state: String? = null) =
        PendingGate.Item(id, lat, lon, t, state)

    @Test
    fun `ровная серия подтверждается с лагом`() {
        // 5 точек, хвост из 2 последних всегда ждет будущего.
        val pts = (0..4).map { pt(it.toLong(), 53.9 + it * 0.001, it * 8000L) }
        val anchor = pt(-1, 53.9 - 0.001, -8000L)
        val res = PendingGate.adjudicate(pts, anchor, pts.last())
        assertEquals(listOf(0L, 1L, 2L), res.confirmed.map { it.id })
        assertTrue(res.vetoed.isEmpty())
    }

    @Test
    fun `круговой вылет ветируется`() {
        // Офис O, вылет E в 4 км, возврат O1/O2: E ветируется.
        val o = pt(-1, 53.9, -8000L)
        val e = pt(10, 53.9 + 0.036, 0L)
        val o1 = pt(11, 53.9, 8000L)
        val o2 = pt(12, 53.9 + 0.00001, 16000L)
        val res = PendingGate.adjudicate(listOf(e, o1, o2), o, o2)
        assertEquals(listOf(10L), res.vetoed.map { it.id })
        assertEquals(
            ru.fogmap.data.FogRepository.VETO_RETURN,
            res.vetoedReasons[10L]
        )
        assertTrue(res.confirmed.isEmpty())
    }

    @Test
    fun `ближний возврат — не вылет`() {
        // Отход на 100 м и возврат: ниже порога вето, подтверждается.
        val o = pt(-1, 53.9, -8000L)
        val a = pt(10, 53.9 + 0.0009, 0L)
        val b = pt(11, 53.9 + 0.00045, 8000L)
        val c = pt(12, 53.9, 16000L)
        val res = PendingGate.adjudicate(listOf(a, b, c), o, c)
        assertEquals(listOf(10L), res.confirmed.map { it.id })
        assertTrue(res.vetoed.isEmpty())
    }

    @Test
    fun `протухшее ожидание ветируется`() {
        // Точка 20-минутной давности без подтверждения: статика не открывается.
        val o = pt(-1, 53.9, -8000L)
        val old = pt(10, 53.9 + 0.0001, 0L, state = "STAND")
        val n1 = pt(11, 53.9 + 0.0001, 1_200_000L, state = "STAND")
        val n2 = pt(12, 53.9 + 0.0001, 1_208_000L, state = "STAND")
        val res = PendingGate.adjudicate(listOf(old, n1, n2), o, n2)
        assertEquals(listOf(10L), res.vetoed.map { it.id })
        assertEquals(
            ru.fogmap.data.FogRepository.VETO_STALE,
            res.vetoedReasons[10L]
        )
    }

    @Test
    fun `протухшее движение подтверждается, а не ветируется`() {
        // fix-eco-signal-loss 2.1: после морозки процесса хвост движения
        // ждал подтверждения дольше 10 минут — он должен открыться.
        val o = pt(-1, 53.9, -8000L)
        val old = pt(10, 53.9 + 0.003, 0L, state = "MOVING")
        val n1 = pt(11, 53.9 + 0.0031, 1_200_000L, state = "MOVING")
        val n2 = pt(12, 53.9 + 0.0032, 1_208_000L, state = "MOVING")
        val res = PendingGate.adjudicate(listOf(old, n1, n2), o, n2)
        assertEquals(listOf(10L), res.confirmed.map { it.id })
        assertTrue(res.vetoed.isEmpty())
    }

    @Test
    fun `короткий хвост целиком ждет`() {
        val pts = (0..1).map { pt(it.toLong(), 53.9 + it * 0.001, it * 8000L) }
        val res = PendingGate.adjudicate(pts, null, pts.last())
        assertTrue(res.confirmed.isEmpty() && res.vetoed.isEmpty())
    }

    @Test
    fun `реплей аэропорта — вылет и возврат не открывают`() {
        // Форма записанного трека 18.09: офис, тишина 30 мин, +4 км,
        // возврат через 8 сек, дальше офис. Эмуляция очереди ворот C:
        // в момент возврата вылет еще в хвосте ожидания.
        val o = pt(-1, 53.9, -8000L)
        val e = pt(10, 53.9 + 0.036, 1_800_000L)
        val r1 = pt(11, 53.9, 1_808_000L)
        val r2 = pt(12, 53.9, 1_816_000L)
        // Хвост [e, r1, r2], свежее r2: разбирается только e.
        val res = PendingGate.adjudicate(listOf(e, r1, r2), o, r2)
        assertEquals(listOf(10L), res.vetoed.map { it.id })
        assertTrue(res.confirmed.isEmpty())
    }
}
