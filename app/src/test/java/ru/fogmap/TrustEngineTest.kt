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
                // Зеркало сервиса и перепрожки: сброс окна с якорем.
                if (it.resetHistory) {
                    val keep = history.lastOrNull()
                    history.clear()
                    if (keep != null) history.add(keep)
                }
                history.add(p)
                TrustEngine.pruneHistory(history, p.time)
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
        // track-fix 19.09: первая точка после тишины — ожидание ворот C,
        // а не вечное вердиктное вето.
        assertTrue(vGap.openFog)
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

    @Test
    fun `старт машины со светофора — движение, а не подозрение`() {
        // track-fix 19.09: 25 серых серий дня начинались с места при чистом
        // GPS. Стоим 6 точек, трогаемся 5.5 м/с, дальше 16 м/с по проспекту.
        val stand = (0 until 6).map { pt(53.9) }
        val pull = pt(53.9 + 0.00045) // ~50 м за 8 с
        val cruise = (1..4).map { pt(53.9 + 0.00045 + it * 0.0012) } // ~16 м/с
        val verdicts = run(stand + listOf(pull) + cruise)
        val vPull = verdicts[6]
        assertEquals(TrustEngine.State.MOVING, vPull.state)
        assertNull(vPull.countReject)
        assertTrue(vPull.resetHistory)
        // Медиана вымыта: крейсерская 16 м/с не дает ложных jump.
        for (v in verdicts.drop(7)) {
            assertEquals(TrustEngine.State.MOVING, v.state)
            assertNull(v.countReject)
        }
        assertTrue(verdicts.last().openFog)
    }

    @Test
    fun `выброс с места все еще подозрение`() {
        // Льгота старта не покрывает настоящий выброс: 250 м за 8 с = 31 м/с.
        val stand = (0 until 6).map { pt(53.9) }
        val jump = pt(53.9 + 0.00225)
        val verdicts = run(stand + listOf(jump))
        val v = verdicts.last()
        assertEquals(TrustEngine.State.SUSPECT, v.state)
        assertEquals(FogRepository.REJECT_JUMP, v.countReject)
        assertFalse(v.resetHistory)
    }

    @Test
    fun `после тишины первая точка в ожидании, а не в вечном вето`() {
        // Стоянка 5 мин, дальше проезд 400 м (тишина покрыла движение):
        // доверие сброшено, но точка — кандидат ворот C.
        val stand = (0 until 6).map { pt(53.9) }
        val afterGap = pt(53.9 + 0.0036, dtS = 300)
        val verdicts = run(stand + listOf(afterGap))
        val v = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, v.state)
        assertEquals(TrustEngine.TRUST_START, v.trust)
        assertTrue(v.openFog)
    }

    @Test
    fun `утренний выезд 838м после ночи — кандидат ворот C без jump`() {
        // wake-balance-parking 3.1: ночь в STANDBY, первый fix в 838 м
        // с чистым accuracy — не вечный SUSPECT, счетчик jump молчит.
        val stand = (0 until 6).map { pt(53.9) }
        val wake = pt(53.9 + 0.0075, dtS = 2000) // ~830 м через 33 мин
        val verdicts = run(stand + listOf(wake))
        val v = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, v.state)
        assertTrue(v.openFog)
        assertNull(v.countReject)
        assertEquals(TrustEngine.TRUST_START, v.trust)
    }

    @Test
    fun `вторая точка пачки сразу после пробуждения — кандидат а не jump`() {
        // wake-balance-parking 3.1: грубый STANDBY-фикс против точного GPS
        // в BURST (52 м за доли секунды, implied артефактно огромен) —
        // продолжение пробуждения, а не выброс.
        val stand = (0 until 6).map { pt(53.9) }
        val coarse = pt(53.9 + 0.0075, dtS = 2000)
        val gps = pt(53.9 + 0.0075 + 0.00047, dtS = 1) // +52 м за 1 с
        val verdicts = run(stand + listOf(coarse, gps))
        val v = verdicts.last()
        assertEquals(TrustEngine.State.MOVING, v.state)
        assertTrue(v.openFog)
        assertNull(v.countReject)
    }

    @Test
    fun `дальний выброс после тишины остается SUSPECT`() {
        // 4 км — аэропорт, а не утренний выезд: телепорт-гейт сильнее льготы.
        val stand = (0 until 6).map { pt(53.9) }
        val far = pt(53.9 + 0.036, dtS = 2000) // +4 км
        val verdicts = run(stand + listOf(far))
        val v = verdicts.last()
        assertEquals(TrustEngine.State.SUSPECT, v.state)
        assertFalse(v.openFog)
        assertEquals(FogRepository.REJECT_JUMP, v.countReject)
    }

    @Test
    fun `ходьба на плотной доставке 1 Гц остается движением`() {
        // fix-eco-signal-loss 1.3: на проде BURST отдавал ~1 Гц, окно из
        // восьми точек покрывало 7 секунд, и идущий человек был STAND.
        val stand = (0 until 45).map { pt(53.9, dtS = 1) }
        val walk = (1..30).map { pt(53.9 + it * 0.0000135, dtS = 1) } // ~1.5 м/с
        val verdicts = run(stand + walk)
        val walkVerdicts = verdicts.drop(stand.size)
        // Первые секунды еще в радиусе якоря, к концу прогулки — уверенно MOVING.
        assertTrue(
            "хвост ходьбы должен быть MOVING, а не STAND",
            walkVerdicts.takeLast(10).all { it.state == TrustEngine.State.MOVING }
        )
    }

    @Test
    fun `стоянка с джиттером на плотной доставке остается STAND`() {
        // 1 Гц, джиттер ±2 м: пары могут давать implied выше 0.5 м/с, но
        // подтвержденная статика не должна выбиваться джиттером.
        val offsetsM = listOf(0.0, 2.0, -2.0, 1.0, -1.0)
        val series = (0 until 60).map {
            pt(53.9 + offsetsM[it % offsetsM.size] / 111_320.0, dtS = 1)
        }
        val verdicts = run(series)
        assertTrue(
            "стоянка не должна выбиваться джиттером",
            verdicts.drop(45).all { it.state == TrustEngine.State.STAND }
        )
        assertFalse(verdicts.last().openFog)
        assertNull(verdicts.last().countReject)
    }

    @Test
    fun `остановка после движения уходит в STAND не позднее минуты`() {
        val walk = (0 until 30).map { pt(53.9 + it * 0.0000135, dtS = 1) }
        val stopPos = 53.9 + 30 * 0.0000135
        val stop = (0 until 70).map { pt(stopPos, dtS = 1) }
        val verdicts = run(walk + stop)
        val stopVerdicts = verdicts.drop(walk.size)
        assertTrue(
            "через минуту стоянки вердикт должен быть STAND",
            stopVerdicts.takeLast(5).all { it.state == TrustEngine.State.STAND }
        )
    }

    @Test
    fun `плотный поток не меняет хвост вердиктов относительно 8 секунд`() {
        // fix-eco-signal-loss 1.4: медиана и потолки не должны зависеть от
        // плотности доставки — разгон 14 м/с после стоянки везде без jump.
        fun drive(dtS: Long): List<TrustEngine.Verdict> {
            val stand = (0 until 8).map { pt(53.9, dtS = dtS) }
            val move = (1..20).map { pt(53.9 + it * 0.0001261 * dtS, dtS = dtS) }
            return run(stand + move)
        }
        val dense = drive(1).takeLast(12).map { it.state to it.countReject }
        val normal = drive(8).takeLast(12).map { it.state to it.countReject }
        assertEquals(normal, dense)
    }

    // --- fix-walk-fog-verdict: speed-гейт, kind, branches ---

    private fun ptSpeed(
        lat: Double, speed: Float?, acc: Float = 10f, dtS: Long = 8
    ): TrustEngine.HistPoint {
        t += dtS * 1000
        return TrustEngine.HistPoint(
            time = t, lat = lat, lon = 27.0, acc = acc, speed = speed
        )
    }

    @Test
    fun `speed гейт 1_0 запрещает STAND и не открывает туман в одиночку`() {
        // 1.1/1.3: speed≥1 при acc≤25 → STAND невозможен, openFog=false.
        val v = TrustEngine.evaluate(null, emptyList(), ptSpeed(53.9, speed = 1.0f))
        assertEquals(TrustEngine.State.MOVING, v.state)
        assertFalse(v.openFog)
        assertTrue("speed_gate в ветках: ${v.branches}", "speed_gate" in v.branches)
        assertEquals(ru.fogmap.tracking.MotionKind.WALK, v.kind)
    }

    @Test
    fun `speed гейт не срабатывает при грязном accuracy`() {
        // acc>25 — гейт не применяется; kind держится STILL гистерезисом
        // (speed 1.0 < 1.3 от STILL), STAND возможен.
        var prev: TrustEngine.PrevState? = null
        val history = ArrayList<TrustEngine.HistPoint>()
        val still = ptSpeed(53.9, speed = null)
        val v0 = TrustEngine.evaluate(prev, history, still)
        prev = v0.next
        history.add(still)
        val dirty = ptSpeed(53.9, speed = 1.0f, acc = 30f)
        val v = TrustEngine.evaluate(prev, history, dirty)
        assertEquals(TrustEngine.State.STAND, v.state)
        assertFalse(v.openFog)
        assertFalse("speed_gate при acc>25", "speed_gate" in v.branches)
    }

    @Test
    fun `kind WALK запрещает STAND даже без speed в HistPoint`() {
        // kind уже классифицирован сервисом/историей; без speed в точке
        // классификация падает на prev — здесь проверяем через speed.
        val still = TrustEngine.evaluate(null, emptyList(), ptSpeed(53.9, speed = null))
        assertEquals(TrustEngine.State.STAND, still.state)
        // Явная ходьба в статике: kind=WALK → MOVING без авт-openFog.
        var prev: TrustEngine.PrevState? = null
        val history = ArrayList<TrustEngine.HistPoint>()
        val stand = ptSpeed(53.9, speed = 0.1f)
        val v0 = TrustEngine.evaluate(prev, history, stand)
        prev = v0.next
        history.add(stand)
        val walk = ptSpeed(53.9 + 0.00002, speed = 1.5f)
        val v1 = TrustEngine.evaluate(prev, history, walk)
        assertEquals(TrustEngine.State.MOVING, v1.state)
        assertFalse(v1.openFog)
        assertEquals(ru.fogmap.tracking.MotionKind.WALK, v1.kind)
    }

    @Test
    fun `ветки вердикта заполняются static jump teleport kind`() {
        val office = (0 until 6).map { ptSpeed(53.9 + it * 0.00002, speed = 0.2f) }
        val standV = run(office).last()
        assertTrue("static: ${standV.branches}", "static" in standV.branches)
        assertEquals(ru.fogmap.tracking.MotionKind.STILL, standV.kind)

        val teleport = run(office + ptSpeed(53.9 + 0.036, speed = null, dtS = 1800)).last()
        assertEquals(TrustEngine.State.SUSPECT, teleport.state)
        assertTrue("teleport: ${teleport.branches}", "teleport" in teleport.branches)

        // kind_* всегда заполнен для каждой точки.
        val speeds = run(listOf(ptSpeed(53.9, speed = 12f), ptSpeed(53.92, speed = 12f)))
        for (v in speeds) {
            assertTrue(v.kind.name in setOf("STILL", "WALK", "BIKE", "VEHICLE"))
        }
        assertEquals(ru.fogmap.tracking.MotionKind.VEHICLE, speeds.last().kind)
    }

    @Test
    fun `afterSilence добавляет ветку silence`() {
        val stand = (0 until 6).map { ptSpeed(53.9, speed = null) }
        val afterGap = ptSpeed(53.9 + 0.0036, speed = 2f, dtS = 300)
        val v = run(stand + listOf(afterGap)).last()
        assertEquals(TrustEngine.State.MOVING, v.state)
        assertTrue("silence: ${v.branches}", "silence" in v.branches)
        assertTrue("wake: ${v.branches}", "wake" in v.branches)
    }

    @Test
    fun `HistPoint несет speed для гейта без второго источника`() {
        val hp = ptSpeed(53.9, speed = 3.3f, acc = 12f)
        assertEquals(3.3f, hp.speed!!, 1e-6f)
        assertTrue(TrustEngine.speedGateBlocksStand(hp))
        val dirty = hp.copy(acc = 30f)
        assertFalse(TrustEngine.speedGateBlocksStand(dirty))
        val slow = hp.copy(speed = 0.5f)
        assertFalse(TrustEngine.speedGateBlocksStand(slow))
        val none = hp.copy(speed = null)
        assertFalse(TrustEngine.speedGateBlocksStand(none))
    }
}
