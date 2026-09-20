package ru.fogmap

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.EcoGovernor

class EcoGovernorTest {
    @Test
    fun `ACTIVE плотный HIGH без изменений`() {
        val p = EcoGovernor.paramsFor(EcoGovernor.Profile.ACTIVE)
        assertEquals(8_000L, p.intervalMs)
        assertEquals(15f, p.distanceM)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, p.priority)
    }

    @Test
    fun `STANDBY редкий BALANCED`() {
        val p = EcoGovernor.paramsFor(EcoGovernor.Profile.STANDBY)
        assertEquals(90_000L, p.intervalMs)
        assertEquals(150f, p.distanceM)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, p.priority)
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
}
