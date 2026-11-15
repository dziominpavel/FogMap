package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.diag.DevLog
import ru.fogmap.tracking.EcoLogPayload

/**
 * change recording-mode, задача 6.1: состав событий записи после снятия
 * эко-режима. В flush больше нет профиля и префикса, в no_fix — профиля.
 */
class EcoLogPayloadTest {

    @Test
    fun `flush несет счетчики пачки и проходит privacy-гейт`() {
        val p = EcoLogPayload.flushPayload(
            batch = 3, rejected = 1, txnMs = "12.3", newCells = 2,
            mode = "hard",
            ecoFix = 45, ecoStand = 40, ecoGpsMs = 15_000
        )
        assertEquals(45L, p[EcoLogPayload.KEY_ECO_FIX])
        assertEquals(40L, p[EcoLogPayload.KEY_ECO_STAND])
        assertEquals(15_000L, p[EcoLogPayload.KEY_ECO_GPS_MS])
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("eco_fix"))
        assertTrue(json.contains("\"mode\":\"hard\""))
        // Эко-полей больше нет: старый формат в лог не возвращается.
        assertTrue(!json.contains("prefix_m"))
        assertTrue(!json.contains("\"profile\""))
    }

    @Test
    fun `no_fix несет фактический gap а не порог`() {
        val p = EcoLogPayload.noFixPayload(gapMs = 720_000L, mode = "hard")
        assertEquals(720_000L, p[EcoLogPayload.KEY_GAP_MS])
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("\"gap_ms\":720000"))
        assertTrue(!json.contains("profile"))
    }

    @Test
    fun `ключи без координат`() {
        val keys = listOf(
            EcoLogPayload.KEY_BATCH, EcoLogPayload.KEY_REJECTED,
            EcoLogPayload.KEY_TXN_MS, EcoLogPayload.KEY_NEW_CELLS,
            EcoLogPayload.KEY_MODE, EcoLogPayload.KEY_ECO_FIX,
            EcoLogPayload.KEY_ECO_STAND, EcoLogPayload.KEY_ECO_GPS_MS,
            EcoLogPayload.KEY_GAP_MS, EcoLogPayload.KEY_SINCE_LAST_MS,
            EcoLogPayload.KEY_BOOT_MONO_MS, EcoLogPayload.KEY_STALE_REPLAY
        )
        for (k in keys) {
            val kl = k.lowercase()
            assertTrue(
                "ключ с координатами запрещен: $k",
                !kl.contains("lat") && !kl.contains("lon")
            )
        }
    }

    @Test
    fun `branchPayload показывает нули всех веток`() {
        val p = EcoLogPayload.branchPayload(mapOf("teleport" to 2L, "kind_WALK" to 7L))
        for (b in ru.fogmap.tracking.TrustEngine.BRANCH_KEYS) {
            assertTrue("нет $b", p.containsKey(b))
        }
        assertEquals(2L, p["teleport"])
        assertEquals(7L, p["kind_WALK"])
        assertEquals(0L, p["static"])
        DevLog.buildPayloadJson(p)
    }

    @Test
    fun `дополнительные поля не появляются без данных`() {
        val p = EcoLogPayload.flushPayload(
            batch = 1, rejected = 0, txnMs = "1.0", newCells = 0,
            mode = "hard", ecoFix = 1, ecoStand = 0, ecoGpsMs = 5_000
        )
        assertNull(p[EcoLogPayload.KEY_SINCE_LAST_MS])
        assertNull(p[EcoLogPayload.KEY_BOOT_MONO_MS])
        assertNull(p[EcoLogPayload.KEY_STALE_REPLAY])
    }
}
