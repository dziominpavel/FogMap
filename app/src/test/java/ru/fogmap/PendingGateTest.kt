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

    // --- fix-pending-gate-false-vetoes (D1/D4) ---

    @Test
    fun `инверсия времени — круговой вылет геометрии не ветуется`() {
        // Морозка 25.09: якорь — новая точка «дом», старые pending-точки
        // маршрута старше якоря. out ~4 км, back ~0 — при старой логике
        // 49 маршрутов ушло в veto_return. Кандидат старше якоря → вето нет.
        // Хвост из 4 точек: разбираются первые две (последние LAG ждут).
        val home = pt(-1, 53.9, 1_000_000L)
        val route = pt(10, 53.9 + 0.036, 100_000L, state = "MOVING")
        val nearHome = pt(11, 53.9 + 0.0001, 150_000L, state = "MOVING")
        val n1 = pt(12, 53.9 + 0.0002, 160_000L, state = "MOVING")
        val n2 = pt(13, 53.9 + 0.0003, 170_000L, state = "MOVING")
        val res = PendingGate.adjudicate(listOf(route, nearHome, n1, n2), home, n2)
        assertTrue("инверсия не должна ветовать: ${res.vetoedReasons}", res.vetoed.isEmpty())
        assertEquals(listOf(10L, 11L), res.confirmed.map { it.id })
    }

    @Test
    fun `якорь не откатывается назад после старого кандидата`() {
        // Якорь новее всех кандидатов. Если бы якорь откатился к первой
        // подтвержденной старой точке A, следующая точка B (в 444 м от A,
        // а новейшая точка хвоста рядом с A) получила бы ложный roundTrip-veto.
        val home = pt(-1, 53.9, 1_000_000L)
        val a = pt(10, 53.9 + 0.036, 100_000L, state = "MOVING")
        val b = pt(11, 53.9 + 0.040, 150_000L, state = "MOVING")
        val n1 = pt(12, 53.9 + 0.0361, 160_000L, state = "MOVING")
        val n2 = pt(13, 53.9 + 0.0362, 170_000L, state = "MOVING")
        val res = PendingGate.adjudicate(listOf(a, b, n1, n2), home, n2)
        assertTrue("откат якоря дал бы ложное вето: ${res.vetoedReasons}", res.vetoed.isEmpty())
        assertEquals(listOf(10L, 11L), res.confirmed.map { it.id })
    }

    @Test
    fun `LAG-точка старше лимита дренируется и получает вето`() {
        // Единственная pending-точка (хвост целиком в LAG) без будущих
        // доставок: раньше actionable был пуст и точка висела вечно.
        val o = pt(-1, 53.9, -8000L)
        val stuck = pt(10, 53.9 + 0.0001, 0L, state = "STAND")
        val newest = pt(11, 53.9 + 0.0001, 700_000L, state = "STAND") // 700 с > 600
        val res = PendingGate.adjudicate(listOf(stuck, newest), o, newest)
        assertEquals(listOf(10L), res.vetoed.map { it.id })
        assertEquals(
            ru.fogmap.data.FogRepository.VETO_STALE,
            res.vetoedReasons[10L]
        )
    }

    @Test
    fun `LAG-движение старше лимита дренируется и подтверждается`() {
        // Те же 2 точки 19:10 25.09: движение после обрыва доставки
        // подтверждается, а не ветуется по возрасту.
        val o = pt(-1, 53.9, -8000L)
        val stuck = pt(10, 53.9 + 0.003, 0L, state = "MOVING")
        val newest = pt(11, 53.9 + 0.0031, 700_000L, state = "MOVING")
        val res = PendingGate.adjudicate(listOf(stuck, newest), o, newest)
        assertTrue(res.vetoed.isEmpty())
        assertEquals(listOf(10L), res.confirmed.map { it.id })
    }

    @Test
    fun `свежий LAG-хвост по-прежнему ждет будущего`() {
        // Дренаж не ломает лаг: точке меньше MAX_PENDING_AGE_S из последних
        // LAG позиций по-прежнему ждать.
        val o = pt(-1, 53.9, -8000L)
        val young = pt(10, 53.9 + 0.003, 1_000_000L, state = "MOVING")
        val newest = pt(11, 53.9 + 0.0031, 1_008_000L, state = "MOVING")
        val res = PendingGate.adjudicate(listOf(young, newest), o, newest)
        assertTrue(res.confirmed.isEmpty() && res.vetoed.isEmpty())
    }
}
