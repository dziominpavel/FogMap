package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.fog.FogGrid
import ru.fogmap.tracking.TrustEngine

/**
 * Серии из gps-trust-filter 1.1/1.2: офисный прыжок, ровная трасса, статика,
 * старт пешком, разворот, плохой accuracy, холодный старт.
 */
class TrustEngineTest {
    private var t = 1_000_000L

    private fun pt(lat: Double, lon: Double = 27.0, acc: Float = 10f, dtS: Long = 8): TrustEngine.HistPoint {
        t += dtS * 1000
        return TrustEngine.HistPoint(time = t, lat = lat, lon = lon, acc = acc)
    }

    /** Прогон серии через движок с историей и состоянием. Возвращает вердикты. */
    private fun run(series: List<TrustEngine.HistPoint>): List<TrustEngine.Verdict> {
        var prev: TrustEngine.PrevState? = null
        val history = ArrayList<TrustEngine.HistPoint>()
        return series.map { p ->
            TrustEngine.evaluate(prev, history, p).also {
                prev = it.next
                history.add(p)
                while (history.size > TrustEngine.HISTORY_MAX) history.removeAt(0)
            }
        }
    }

    @Test
    fun `холодный старт — стоим, туман закрыт, счетчиков нет`() {
        val v = run(listOf(pt(53.9))).single()
        assertEquals(TrustEngine.State.STAND, v.state)
        assertFalse(v.openFog)
        assertNull(v.countReject)
    }

    @Test
    fun `аэропорт через тишину — телепорт независимо от малой implied`() {
        // Офис, тишина 30 мин, уверенный фикс в 4 км: implied всего 2.2 м/с,
        // старые ворота пропустили бы как «спокойный шаг».
        val office = (0 until 6).map { pt(53.9 + it * 0.00002) }
        val teleport = pt(53.9 + 0.036, dtS = 1800) // +4км через 30 мин
        val verdicts = run(office + teleport)
        val v = verdicts.last()
        assertEquals(TrustEngine.State.SUSPECT, v.state)
        assertFalse(v.openFog)
        assertEquals(FogRepository.REJECT_JUMP, v.countReject)
    }

    @Test
    fun `возврат из телепорта подтверждает выброс`() {
        val office = (0 until 6).map { pt(53.9 + it * 0.00002) }
        val teleport = pt(53.9 + 0.036, dtS = 1800)
        val back = pt(53.9 + 0.00003) // вернулась через 8 сек
        val verdicts = run(office + teleport + back)
        // Обратная нога сама 4 км, но возврат в якорь важнее телепорта.
        val vBack = verdicts.last()
        assertEquals(TrustEngine.State.STAND, vBack.state)
        assertFalse(vBack.openFog)
    }

    @Test
    fun `выезд из тоннеля — серия продолжается без ложного jump`() {
        // 14 м/с (мягкий холодный старт без подозрений), разрыв доставок
        // 180 сек, дальше те же 14 м/с: телепорт-гейт пропускает (серия
        // доказала скорость), тишина сбрасывает доверие в стартовое.
        val series = (0..5).map { pt(53.9 + it * 0.00126) }
        val afterGap = pt(53.9 + 5 * 0.00126 + 0.0226, dtS = 180) // +2.5км за 180с
        val onward = pt(53.9 + 5 * 0.00126 + 0.0226 + 0.00126)
        val verdicts = run(series + afterGap + onward)
        val vGap = verdicts[6]
        assertEquals(TrustEngine.State.MOVING, vGap.state)
        assertNull(vGap.countReject)
        // Без сброса было бы 100 (доверие набрано серией).
        assertEquals(TrustEngine.TRUST_START, vGap.trust)
        assertFalse(vGap.openFog)
        val vNext = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, vNext.state)
        assertTrue(vNext.openFog)
    }

    @Test
    fun `честная трасса 150 км в ч — серия подтверждается`() {
        // 41 м/с каждые 8 сек = 328 м < телепорта 500 м.
        val series = (0 until 6).map { pt(53.9 + it * 0.00295) }
        val verdicts = run(series)
        // Холодный старт сразу на скорости: первый кадр подозрителен
        // (контекста нет), дальше серия подтверждает движение.
        assertEquals(TrustEngine.State.SUSPECT, verdicts[1].state)
        val last = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, last.state)
        assertNull(last.countReject)
        assertTrue(last.openFog)
    }

    @Test
    fun `тишина без смещения — стоим закрыто`() {
        val office = (0 until 6).map { pt(53.9 + it * 0.00002) }
        val same = pt(53.9 + 0.00001, dtS = 3600) // та же куча через час
        val verdicts = run(office + same)
        val v = verdicts.last()
        assertEquals(TrustEngine.State.STAND, v.state)
        assertFalse(v.openFog)
        assertNull(v.countReject)
    }

    @Test
    fun `офисный прыжок туда-обратно — подозрение, потом подтверждение выброса`() {
        val office = (0 until 6).map { pt(53.9 + it * 0.00002) } // статика, джиттер ~2м
        val jump = pt(53.9 + 0.00225) // +250м за 8 сек
        val back = pt(53.9 + 0.00003) // вернулась назад
        val verdicts = run(office + jump + back)
        val vJump = verdicts[6]
        assertEquals(TrustEngine.State.SUSPECT, vJump.state)
        assertFalse(vJump.openFog)
        assertEquals(FogRepository.REJECT_JUMP, vJump.countReject)
        val vBack = verdicts[7]
        assertEquals(TrustEngine.State.STAND, vBack.state)
        assertFalse(vBack.openFog)
    }

    @Test
    fun `ровная трасса набирает доверие и открывает туман`() {
        // 14 м/с строго на север, accuracy 10.
        val series = (0 until 6).map { pt(53.9 + it * 0.001) }
        val verdicts = run(series)
        val last = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, last.state)
        assertTrue("доверие ${last.trust}", last.trust >= FogGrid.TRUST_HIGH)
        assertTrue(last.openFog)
        assertNull(last.countReject)
        // Рост медленный: первая точка движения еще не HIGH.
        assertTrue(verdicts[1].trust < FogGrid.TRUST_HIGH)
    }

    @Test
    fun `статика замораживает открытие без счетчиков`() {
        val series = (0 until 7).map { pt(53.9 + (it % 3) * 0.00002) }
        val verdicts = run(series)
        val last = verdicts.last()
        assertEquals(TrustEngine.State.STAND, last.state)
        assertFalse(last.openFog)
        assertNull(last.countReject)
    }

    @Test
    fun `старт пешком — движение, а не подозрение`() {
        val office = (0 until 5).map { pt(53.9) }
        // 1.5 м/с на север: первые две точки еще в радиусе якоря, третья выходит.
        val walk = (1..3).map { pt(53.9 + it * 0.000108) }
        val verdicts = run(office + walk)
        assertEquals(TrustEngine.State.STAND, verdicts[5].state)
        val vGo = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, vGo.state)
        assertNull(vGo.countReject)
        assertTrue(vGo.openFog)
    }

    @Test
    fun `резкий разворот на скорости — подозрение`() {
        val north = (0..2).map { pt(53.9 + it * 0.001) } // 14 м/с на север
        val south = pt(53.9 + 2 * 0.001 - 0.001) // назад на юг с той же скоростью
        val verdicts = run(north + south)
        val v = verdicts.last()
        assertEquals(TrustEngine.State.SUSPECT, v.state)
        assertFalse(v.openFog)
    }

    @Test
    fun `плохой accuracy упирает доверие в потолок`() {
        val series = (0 until 8).map { pt(53.9 + it * 0.001, acc = 20f) }
        val last = run(series).last()
        assertEquals(TrustEngine.State.MOVING, last.state)
        assertTrue("доверие ${last.trust}", last.trust <= 60)
        assertTrue(last.openFog) // средняя кисть, не ноль
    }
}
