package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.EcoGovernor
import ru.fogmap.tracking.MotionActivity
import ru.fogmap.tracking.MotionMachine
import ru.fogmap.tracking.MotionState
import ru.fogmap.tracking.TrackingWake

/**
 * motion-state 1.1/1.2/3.1: одно состояние движения и быстрый старт.
 *
 * Полевой разбор 09.11: после тишины первый фикс уходил в SUSPECT, и только
 * третий подтверждал движение — 2–3 минуты поездки терялись. Здесь проверяется,
 * что одиночный правдоподобный фикс переводит в MOVING сразу.
 */
class MotionStateTest {

    private val interval = 60_000L

    private fun machine() = MotionMachine()

    @Test
    fun `одиночный правдоподобный фикс после тишины — сразу MOVING`() {
        val m = machine()
        val st = m.onFix(nowMs = 1_000L, speedMps = 8f, accM = 10f, gapMs = 600_000L, intervalMs = interval)
        assertEquals(MotionState.MOVING, st)
        assertTrue("это и есть fast-path", m.lastFixWasFastPath)
    }

    @Test
    fun `грязный фикс не поднимает движение`() {
        val m = machine()
        val st = m.onFix(nowMs = 1_000L, speedMps = 8f, accM = 60f, gapMs = 600_000L, intervalMs = interval)
        assertEquals(MotionState.UNKNOWN, st)
    }

    @Test
    fun `движение держится окном удержания и гаснет тишиной`() {
        val m = machine()
        m.onFix(nowMs = 0L, speedMps = 5f, accM = 10f, gapMs = 0L, intervalMs = interval)
        assertEquals(MotionState.MOVING, m.state)
        // Пробка: фиксы без скорости не роняют профиль мгновенно.
        m.onFix(nowMs = 30_000L, speedMps = 0f, accM = 10f, gapMs = 30_000L, intervalMs = interval)
        assertEquals(MotionState.MOVING, m.state)
        assertEquals(MotionState.STILL, m.tick(nowMs = 30_000L + EcoGovernor.ACTIVE_SPEED_HOLD_MS + 1))
    }

    @Test
    fun `переход активности в ходьбу поднимает движение`() {
        val m = machine()
        assertEquals(MotionState.MOVING, m.onActivity(MotionActivity.WALK, nowMs = 5_000L))
    }

    @Test
    fun `тишина не роняет движение раньше окна`() {
        val m = machine()
        m.onActivity(MotionActivity.WALK, nowMs = 0L)
        assertEquals(MotionState.MOVING, m.onActivity(MotionActivity.STILL, nowMs = 1_000L))
        assertEquals(MotionState.STILL, m.onActivity(MotionActivity.STILL, nowMs = 200_000L))
    }

    @Test
    fun `план записи выводится из состояния`() {
        val moving = MotionMachine.planFor(MotionState.MOVING, EcoGovernor.Profile.STANDBY)
        assertEquals(EcoGovernor.Profile.ACTIVE, moving.profile)
        assertTrue(moving.holdWake)

        val still = MotionMachine.planFor(MotionState.STILL, EcoGovernor.Profile.ACTIVE)
        assertEquals(EcoGovernor.Profile.STANDBY, still.profile)
        assertFalse(still.holdWake)

        val unknown = MotionMachine.planFor(MotionState.UNKNOWN, EcoGovernor.Profile.BURST)
        assertEquals(EcoGovernor.Profile.BURST, unknown.profile)
    }

    @Test
    fun `STANDBY расслабляется только при доступных переходах`() {
        assertEquals(EcoGovernor.STANDBY_INTERVAL_MS, EcoGovernor.standbyIntervalMs(false))
        assertEquals(EcoGovernor.STANDBY_RELAXED_INTERVAL_MS, EcoGovernor.standbyIntervalMs(true))
        assertTrue(EcoGovernor.STANDBY_RELAXED_INTERVAL_MS > EcoGovernor.STANDBY_INTERVAL_MS)
        // Профиль STANDBY реально берёт расслабленный интервал.
        assertEquals(
            EcoGovernor.STANDBY_RELAXED_INTERVAL_MS,
            EcoGovernor.paramsFor(EcoGovernor.Profile.STANDBY, EcoGovernor.standbyIntervalMs(true)).intervalMs
        )
    }

    @Test
    fun `wake-lock нужен только движению`() {
        assertTrue(TrackingWake.neededFor(EcoGovernor.Profile.ACTIVE, paused = false))
        assertTrue(TrackingWake.neededFor(EcoGovernor.Profile.BURST, paused = false))
        assertFalse(TrackingWake.neededFor(EcoGovernor.Profile.STANDBY, paused = false))
    }
}
