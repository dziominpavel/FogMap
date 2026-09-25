package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.db.TrackPointEntity

/**
 * fix-pending-gate-false-vetoes E (D5): разовый ремонт ложных вето.
 *
 * Три гарантии спеки «Восстановление после ложного вето»:
 *  1. ложные `veto_return` (инверсия времени, морозка) переоткрываются;
 *  2. честные `veto_return`/`veto_stale` остаются закрытыми;
 *  3. повторный проход идемпотентен.
 * Плюс дренаж застрявших `fogOpened=0` (D4 для прошлых дней): движение
 * подтверждается, статика честно получает `veto_stale`.
 */
class RepairPlanTest {
    /** Дом (25.09: старт/финиш маршрута). */
    private val home = 53.944 to 27.696
    /** Маршрут ~2.5 км от дома. */
    private val route = 53.96 to 27.67
    /** Офис и точка «вылета» ~2.4 км от него (форма трека 18.09). */
    private val office = 53.90 to 27.56
    private val flight = 53.936 to 27.56

    private fun tp(
        id: Long,
        t: Long,
        pos: Pair<Double, Double>,
        fog: Int = 0,
        state: String = "MOVING",
        reason: String? = null
    ) = TrackPointEntity(
        id = id, trackId = 1, time = t,
        lat = pos.first, lon = pos.second,
        acc = 8f, speed = 10f, trust = 100, state = state,
        rejectReason = reason, fogOpened = fog
    )

    /** День 25.09 в миниатюре: орфаны маршрута ветованы, соседи открыты. */
    private fun falseVetoDay() = listOf(
        tp(1, 0, home, fog = 1),                                    // открыта до поездки
        tp(2, 3_600_000, route, fog = 2, reason = "veto_return"),   // орфан 18:05
        tp(3, 3_604_000, route, fog = 2, reason = "veto_return"),   // орфан 18:06
        tp(4, 3_608_000, route, fog = 1),                           // открыта живым flush
        tp(5, 5_400_000, home, fog = 1)                             // вечер дома
    )

    /** День с честным круговым вылетом (форма трека 18.09). */
    private fun honestRoundTripDay() = listOf(
        tp(1, 0, office, fog = 1),
        tp(2, 1_800_000, flight, fog = 2, reason = "veto_return"),  // вылет
        tp(3, 1_808_000, office, fog = 1),                          // возврат
        tp(4, 1_816_000, office, fog = 1),
        tp(5, 5_400_000, office, fog = 1)                           // день в офисе
    )

    @Test
    fun `ложные veto_return маршрута переоткрываются`() {
        val plan = FogRepository.planRepair(falseVetoDay())
        assertEquals("орфаны должны подтвердиться", listOf(2L, 3L), plan.confirm.map { it.id })
        assertTrue("вето неожиданно назначен", plan.veto.isEmpty())
    }

    @Test
    fun `честный круговой вылет не переоткрывается`() {
        val plan = FogRepository.planRepair(honestRoundTripDay())
        assertTrue("честный вылет подтвердился — баг", plan.confirm.isEmpty())
        assertEquals(mapOf(2L to "veto_return"), plan.veto)
    }

    @Test
    fun `честное veto_stale и вердиктно закрытые строки не трогаются`() {
        val day = listOf(
            tp(1, 0, office, fog = 1),
            tp(2, 1_000_000, office, fog = 2, state = "STAND", reason = "veto_stale"),
            tp(3, 2_200_000, office, fog = 2, state = "STAND")       // openFog=false при записи
        )
        val plan = FogRepository.planRepair(day)
        assertTrue(plan.confirm.isEmpty())
        assertTrue(plan.veto.isEmpty())
    }

    @Test
    fun `застрявший хвост дренируется — движение подтверждается`() {
        val day = listOf(
            tp(1, 0, home, fog = 1),
            tp(2, 60_000, home, fog = 0),                            // застрял в LAG
            tp(3, 760_000, home, fog = 1)                            // новейшая точка трека
        )
        val plan = FogRepository.planRepair(day)
        assertEquals(listOf(2L), plan.confirm.map { it.id })
        assertTrue(plan.veto.isEmpty())
    }

    @Test
    fun `застрявшая статика старше порога получает veto_stale`() {
        val day = listOf(
            tp(1, 0, home, fog = 1),
            tp(2, 60_000, home, fog = 0, state = "STAND"),
            tp(3, 760_000, home, fog = 1)
        )
        val plan = FogRepository.planRepair(day)
        assertTrue(plan.confirm.isEmpty())
        assertEquals(mapOf(2L to "veto_stale"), plan.veto)
    }

    @Test
    fun `повторный проход идемпотентен — план не меняется`() {
        // Применяем план так же, как repairVetoed в транзакции, и пересчитываем.
        for (day in listOf(falseVetoDay(), honestRoundTripDay())) {
            val plan1 = FogRepository.planRepair(day)
            val applied = day.map { p ->
                when {
                    plan1.confirm.any { it.id == p.id } ->
                        p.copy(fogOpened = 1, rejectReason = null)
                    plan1.veto.containsKey(p.id) ->
                        p.copy(fogOpened = 2, rejectReason = plan1.veto[p.id])
                    else -> p
                }
            }
            val plan2 = FogRepository.planRepair(applied)
            assertTrue("повторный проход что-то переоткрыл", plan2.confirm.isEmpty())
            assertEquals("повторный проход изменил вето", plan1.veto, plan2.veto)
        }
    }
}
