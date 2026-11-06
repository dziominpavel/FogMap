package ru.fogmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.EcoGovernor
import ru.fogmap.tracking.TrackingWake

/**
 * field-hardening 1.1: CPU держим только на движении. Стоянка (STANDBY) и
 * пауза не должны превращать трекер в «вечно бодрствующий» процесс.
 */
class TrackingWakeTest {

    @Test
    fun `движение держит wake-lock`() {
        assertTrue(TrackingWake.neededFor(EcoGovernor.Profile.ACTIVE, paused = false))
        assertTrue(TrackingWake.neededFor(EcoGovernor.Profile.BURST, paused = false))
    }

    @Test
    fun `стоянка и пауза не держат`() {
        assertFalse(TrackingWake.neededFor(EcoGovernor.Profile.STANDBY, paused = false))
        assertFalse(TrackingWake.neededFor(EcoGovernor.Profile.ACTIVE, paused = true))
        assertFalse(TrackingWake.neededFor(EcoGovernor.Profile.BURST, paused = true))
        assertFalse(TrackingWake.neededFor(EcoGovernor.Profile.STANDBY, paused = true))
    }
}
