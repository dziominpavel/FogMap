package ru.fogmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.TrackingWake

/**
 * change recording-mode, задача 2.2: CPU держим всё время записи, а не только
 * в движении. Единственное условие — включённый тумблер.
 */
class TrackingWakeTest {

    @Test
    fun `включённая запись держит wake-lock`() {
        assertTrue(TrackingWake.neededFor(recording = true))
    }

    @Test
    fun `выключенная запись не держит`() {
        assertFalse(TrackingWake.neededFor(recording = false))
    }
}
