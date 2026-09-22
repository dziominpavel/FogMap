package ru.fogmap

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.EcoGovernor
import ru.fogmap.tracking.TrustEngine

class EcoGovernorTest {
    @Test
    fun `ACTIVE плотный HIGH без изменений`() {
        val p = EcoGovernor.paramsFor(EcoGovernor.Profile.ACTIVE)
        assertEquals(8_000L, p.intervalMs)
        assertEquals(15f, p.distanceM)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, p.priority)
    }

    @Test
    fun `STANDBY редкий HIGH без BALANCED`() {
        val p = EcoGovernor.paramsFor(EcoGovernor.Profile.STANDBY)
        assertEquals(60_000L, p.intervalMs)
        assertEquals(30_000L, p.minIntervalMs)
        assertEquals(0f, p.distanceM)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, p.priority)
    }

    @Test
    fun `BURST короткий HIGH`() {
        val p = EcoGovernor.paramsFor(EcoGovernor.Profile.BURST)
        assertEquals(6_000L, p.intervalMs)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, p.priority)
    }

    @Test
    fun `пробуждение по смещению 100м`() {
        // ~111 м по широте от якоря.
        assertTrue(EcoGovernor.isWakeSignal(55.0, 37.0, 55.001, 37.0))
        assertFalse(EcoGovernor.isWakeSignal(55.0, 37.0, 55.0001, 37.0))
    }

    @Test
    fun `имена профилей round-trip`() {
        assertEquals(EcoGovernor.Profile.STANDBY, EcoGovernor.fromName(EcoGovernor.nameOf(EcoGovernor.Profile.STANDBY)))
        assertEquals(EcoGovernor.Profile.ACTIVE, EcoGovernor.fromName(null))
        assertEquals(EcoGovernor.Profile.ACTIVE, EcoGovernor.fromName("MUTED"))
    }

    @Test
    fun `STANDBY GPS без motion будит`() {
        // Телефон неподвижно на сиденье: входа с motion в решении нет намеренно.
        assertEquals(
            EcoGovernor.Profile.BURST,
            EcoGovernor.standbyTarget(TrustEngine.State.STAND, 146L)
        )
        assertEquals(
            null,
            EcoGovernor.standbyTarget(TrustEngine.State.STAND, 9L)
        )
        assertEquals(
            EcoGovernor.Profile.BURST,
            EcoGovernor.standbyTarget(TrustEngine.State.MOVING, 9L)
        )
    }

    @Test
    fun `speed-latch границы`() {
        assertTrue(EcoGovernor.isSpeedLatch(10f, 10f))
        assertTrue(EcoGovernor.isSpeedLatch(5.01f, 25f))
        assertFalse(EcoGovernor.isSpeedLatch(5.0f, 10f))
        assertFalse(EcoGovernor.isSpeedLatch(10f, 25.1f))
        assertFalse(EcoGovernor.isSpeedLatch(null, 10f))
        assertFalse(EcoGovernor.isSpeedLatch(10f, null))
    }

    @Test
    fun `BURST скорость с грязной историей уходит в ACTIVE`() {
        assertEquals(
            EcoGovernor.Profile.ACTIVE,
            EcoGovernor.burstTarget(TrustEngine.State.MOVING, 0f, 10f)
        )
        assertEquals(
            EcoGovernor.Profile.ACTIVE,
            EcoGovernor.burstTarget(TrustEngine.State.STAND, 10f, 10f)
        )
        assertEquals(
            null,
            EcoGovernor.burstTarget(TrustEngine.State.STAND, 10f, 60f)
        )
        assertEquals(
            null,
            EcoGovernor.burstTarget(TrustEngine.State.STAND, 1f, 10f)
        )
    }

    @Test
    fun `пробка 2 мин не роняет ACTIVE`() {
        val now = 1_000_000L
        // Серия и дебаунс набраны, но скорость была минуту назад — стоим в ACTIVE.
        assertFalse(
            EcoGovernor.activeMayStandby(
                standStreak = 5,
                nowMs = now,
                lastStandbyEnterMs = now - 60_000L,
                lastSpeedLatchMs = now - 60_000L
            )
        )
        // Скорости не было вообще — обычный уход по STAND.
        assertTrue(
            EcoGovernor.activeMayStandby(
                standStreak = 5,
                nowMs = now,
                lastStandbyEnterMs = now - 60_000L,
                lastSpeedLatchMs = 0L
            )
        )
        // Удержание вышло (4+ мин после скорости) — можно в STANDBY.
        assertTrue(
            EcoGovernor.activeMayStandby(
                standStreak = 5,
                nowMs = now,
                lastStandbyEnterMs = now - 300_000L,
                lastSpeedLatchMs = now - 240_000L
            )
        )
        // Серия не набрана — рано.
        assertFalse(
            EcoGovernor.activeMayStandby(
                standStreak = 4,
                nowMs = now,
                lastStandbyEnterMs = now - 300_000L,
                lastSpeedLatchMs = 0L
            )
        )
    }
}
