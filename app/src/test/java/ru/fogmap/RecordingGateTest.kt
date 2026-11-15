package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.tracking.RecordingGate
import ru.fogmap.tracking.RecordingGate.Decision

/**
 * change recording-mode, задача 1.2: одно решение на все точки запуска.
 * Выключенный тумблер обязан побеждать любые другие условия — иначе запись
 * поднимается против воли владельца, а это ровно то, что чинили.
 */
class RecordingGateTest {

    @Test
    fun `выключенная запись не запускается ни при каких условиях`() {
        assertEquals(
            Decision.SKIP_DISABLED,
            RecordingGate.decide(recording = false, playServices = true, canTrack = true)
        )
        assertEquals(
            Decision.SKIP_DISABLED,
            RecordingGate.decide(recording = false, playServices = false, canTrack = false)
        )
    }

    @Test
    fun `без play services не запускаем`() {
        assertEquals(
            Decision.SKIP_PLAY_SERVICES,
            RecordingGate.decide(recording = true, playServices = false, canTrack = true)
        )
    }

    @Test
    fun `без разрешения гео не запускаем`() {
        assertEquals(
            Decision.SKIP_NO_PERMISSION,
            RecordingGate.decide(recording = true, playServices = true, canTrack = false)
        )
    }

    @Test
    fun `все условия выполнены — запускаем`() {
        assertEquals(
            Decision.START,
            RecordingGate.decide(recording = true, playServices = true, canTrack = true)
        )
    }

    @Test
    fun `имена исходов совпадают с контрактом лога`() {
        assertEquals("started", RecordingGate.outcomeName(Decision.START))
        assertEquals("skip_disabled", RecordingGate.outcomeName(Decision.SKIP_DISABLED))
        assertEquals("skip_play_services", RecordingGate.outcomeName(Decision.SKIP_PLAY_SERVICES))
        assertEquals("skip_no_permission", RecordingGate.outcomeName(Decision.SKIP_NO_PERMISSION))
    }
}
