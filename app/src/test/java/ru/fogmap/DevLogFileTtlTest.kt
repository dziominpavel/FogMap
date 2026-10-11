package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.diag.DevLogFile

/**
 * fix-devlog-retention-7d: граница TTL диагностических логов.
 * Чистый предикат из companion — тестируется без Android Context.
 */
class DevLogFileTtlTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun `ttl равен семи дням`() {
        assertEquals(7L * dayMs, DevLogFile.TTL_MS)
    }

    @Test
    fun `возраст ровно ttl - файл жив`() {
        // Удаляется только строго старше TTL (как в cleanupExpired).
        assertFalse(DevLogFile.isExpired(now - DevLogFile.TTL_MS, now))
    }

    @Test
    fun `возраст ttl плюс миллисекунда - удаляется`() {
        assertTrue(DevLogFile.isExpired(now - DevLogFile.TTL_MS - 1L, now))
    }

    @Test
    fun `шесть дней - жив, восемь дней - удаляется`() {
        assertFalse(DevLogFile.isExpired(now - 6L * dayMs, now))
        assertTrue(DevLogFile.isExpired(now - 8L * dayMs, now))
    }
}
