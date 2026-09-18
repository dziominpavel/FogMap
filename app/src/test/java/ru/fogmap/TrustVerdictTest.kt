package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.tracking.TrustEngine

/**
 * Схема вердикта trust-v2 1.2: state/причина стабильны, дефолты старых строк
 * соответствуют до-лаговому поведению (должны совпадать с MIGRATION_4_5).
 */
class TrustVerdictTest {
    @Test
    fun `имена состояний стабильны`() {
        assertEquals(
            setOf("STAND", "MOVING", "SUSPECT"),
            TrustEngine.State.entries.map { it.name }.toSet()
        )
        // valueOf — тот же контракт, что читает будущая переобработка.
        assertEquals(TrustEngine.State.SUSPECT, TrustEngine.State.valueOf("SUSPECT"))
    }

    @Test
    fun `причины отбросов различны и непусты`() {
        val keys = FogRepository.REJECT_REASONS
        assertTrue(keys.contains(FogRepository.REJECT_JUMP))
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test
    fun `дефолты сущности — открытые старые строки`() {
        // Должны совпадать с DEFAULT в MIGRATION_4_5 (Room сверяет схему).
        val e = TrackPointEntity(trackId = 1, time = 0, lat = 0.0, lon = 0.0, acc = 0f, speed = null)
        assertEquals(100, e.trust)
        assertEquals("MOVING", e.state)
        assertNull(e.rejectReason)
        assertEquals(1, e.fogOpened)
    }

    @Test
    fun `дефолты сырой точки — доверенная`() {
        val p = RawPoint(time = 0, lat = 0.0, lon = 0.0, acc = 0f, speed = null)
        assertEquals(100, p.trust)
        assertEquals("MOVING", p.state)
        assertNull(p.rejectReason)
    }
}
