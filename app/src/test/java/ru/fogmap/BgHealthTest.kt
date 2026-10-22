package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.BgHealth
import ru.fogmap.tracking.TrackingLife

/**
 * fix-track-gap-visibility 5.1–5.4: классификация состояния фоновой записи.
 * Экран не дает инструкций — он показывает измеренные причины, поэтому вся
 * логика «что считать проблемой» живет здесь и тестируется без Android.
 */
class BgHealthTest {

    private fun snapshot(
        paused: Boolean = false,
        lastPointAgeMs: Long? = 12_000L,
        lastHeartbeatAgeMs: Long? = 60_000L,
        notificationsEnabled: Boolean = true,
        batteryRestricted: Boolean = false,
        lastExit: BgHealth.Exit? = null
    ) = BgHealth.Snapshot(
        paused, lastPointAgeMs, lastHeartbeatAgeMs,
        notificationsEnabled, batteryRestricted, lastExit
    )

    @Test
    fun `запись идет только когда не пауза и точка свежая`() {
        assertTrue(BgHealth.isRecording(snapshot()))
        assertFalse(BgHealth.isRecording(snapshot(paused = true)))
        assertFalse(BgHealth.isRecording(snapshot(lastPointAgeMs = 11 * 60_000L)))
        assertFalse("без точек записи нет", BgHealth.isRecording(snapshot(lastPointAgeMs = null)))
    }

    @Test
    fun `порог свежести точки десять минут`() {
        assertFalse(BgHealth.isStalePoint(BgHealth.STALE_POINT_MS))
        assertTrue(BgHealth.isStalePoint(BgHealth.STALE_POINT_MS + 1))
        assertTrue(BgHealth.isStalePoint(null))
    }

    @Test
    fun `порог heartbeat пятнадцать минут`() {
        assertFalse(BgHealth.isStaleHeartbeat(BgHealth.STALE_HEARTBEAT_MS))
        assertTrue(BgHealth.isStaleHeartbeat(BgHealth.STALE_HEARTBEAT_MS + 1))
        assertTrue(BgHealth.isStaleHeartbeat(null))
    }

    @Test
    fun `здоровое состояние не дает проблем`() {
        assertEquals(emptyList<BgHealth.Problem>(), BgHealth.problems(snapshot()))
    }

    @Test
    fun `каждая причина имеет свой пункт`() {
        assertEquals(
            listOf(BgHealth.Problem.NOTIFICATIONS),
            BgHealth.problems(snapshot(notificationsEnabled = false))
        )
        assertEquals(
            listOf(BgHealth.Problem.BATTERY),
            BgHealth.problems(snapshot(batteryRestricted = true))
        )
        val killed = BgHealth.Exit("low_memory", 120_000L, null, systemStop = true)
        assertEquals(
            listOf(BgHealth.Problem.KILLED),
            BgHealth.problems(snapshot(lastExit = killed))
        )
    }

    @Test
    fun `нет свежих точек — отдельный пункт только вне паузы`() {
        assertEquals(
            listOf(BgHealth.Problem.NO_RECENT_POINTS),
            BgHealth.problems(snapshot(lastPointAgeMs = 3 * 3_600_000L))
        )
        // На паузе отсутствие точек ожидаемо — не проблема.
        assertEquals(
            emptyList<BgHealth.Problem>(),
            BgHealth.problems(snapshot(paused = true, lastPointAgeMs = 3 * 3_600_000L))
        )
    }

    @Test
    fun `несколько проблем показываются все в стабильном порядке`() {
        val killed = BgHealth.Exit("anr", 60_000L, null, systemStop = true)
        val problems = BgHealth.problems(
            snapshot(
                lastPointAgeMs = 3_600_000L,
                notificationsEnabled = false,
                batteryRestricted = true,
                lastExit = killed
            )
        )
        assertEquals(
            listOf(
                BgHealth.Problem.NOTIFICATIONS,
                BgHealth.Problem.BATTERY,
                BgHealth.Problem.KILLED,
                BgHealth.Problem.NO_RECENT_POINTS
            ),
            problems
        )
    }

    @Test
    fun `остановка системой отличается от нашего выхода`() {
        assertTrue(BgHealth.isSystemStop("low_memory", null))
        assertTrue(BgHealth.isSystemStop("anr", null))
        assertTrue(BgHealth.isSystemStop("freezer", null))
        assertTrue(BgHealth.isSystemStop("user_requested", null))
        // Вендорский тег сам по себе — признак OEM-убийства.
        assertTrue(BgHealth.isSystemStop("other", "iAwareF[Thermal]"))
        // Штатный выход по нашей инициативе проблемой не считается.
        assertFalse(BgHealth.isSystemStop("exit_self", null))
        assertFalse(BgHealth.isSystemStop("other", null))
    }

    @Test
    fun `payload причины смерти разбирается в модель`() {
        val payload = mapOf(
            TrackingLife.KEY_REASON to "low_memory",
            TrackingLife.KEY_AGE_MS to 300_000L,
            TrackingLife.KEY_VENDOR to "iAwareF[Thermal]"
        )
        val exit = BgHealth.exitFrom(payload)!!
        assertEquals("low_memory", exit.reason)
        assertEquals(300_000L, exit.ageMs)
        assertEquals("iAwareF[Thermal]", exit.vendor)
        assertTrue(exit.systemStop)
        assertNull(BgHealth.exitFrom(emptyMap()))
    }

    @Test
    fun `возраст не бывает отрицательным`() {
        assertEquals(5_000L, BgHealth.ageMs(10_000L, 5_000L))
        assertEquals(0L, BgHealth.ageMs(10_000L, 15_000L))
        assertNull(BgHealth.ageMs(10_000L, null))
    }
}
