package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.StaleReplay

/**
 * field-hardening 1.3: пачка, отложенная заморозкой, приходит одним приёмом.
 * Метка приёма подменяется меткой фикса только для отложенных и только если
 * время идёт вперёд — иначе доверие видит «35 точек за 0 секунд».
 */
class StaleReplayTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `свежий фикс остаётся на моменте приёма`() {
        assertEquals(now, StaleReplay.recvFor(now, now - 5_000L, null))
        assertEquals(now, StaleReplay.recvFor(now, now, null))
    }

    @Test
    fun `отложенный фикс получает своё время`() {
        val fix = now - 90 * 60_000L // 1.5 часа в очереди
        assertEquals(fix, StaleReplay.recvFor(now, fix, now - 3 * 60 * 60_000L))
        assertTrue(StaleReplay.replayed(now, fix))
    }

    @Test
    fun `повтор метки провайдера не откатывает время назад`() {
        val lastRecv = now - 40 * 60_000L
        val fix = now - 90 * 60_000L // старее последней принятой
        assertEquals(now, StaleReplay.recvFor(now, fix, lastRecv))
        assertEquals(now, StaleReplay.recvFor(now, lastRecv, lastRecv))
        assertFalse(StaleReplay.replayed(now, now))
    }

    @Test
    fun `битая метка фикса не используется`() {
        assertEquals(now, StaleReplay.recvFor(now, 0L, null))
        assertEquals(now, StaleReplay.recvFor(now, now + 60_000L, null))
    }

    @Test
    fun `порог отложенности`() {
        // Ровно на пороге — ещё живой фикс.
        assertEquals(now, StaleReplay.recvFor(now, now - StaleReplay.STALE_MS, null))
        val older = now - StaleReplay.STALE_MS - 1L
        assertEquals(older, StaleReplay.recvFor(now, older, null))
    }
}
