package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.diag.DevLog
import ru.fogmap.tracking.EcoLogPayload

class EcoLogPayloadTest {
    @Test
    fun `flush несет эко-снимок и проходит privacy-гейт`() {
        val p = EcoLogPayload.flushPayload(
            batch = 3, rejected = 1, txnMs = "12.3", newCells = 2,
            mode = "eco", profile = "STANDBY",
            ecoFix = 45, ecoStand = 40, ecoGpsMs = 15_000
        )
        assertEquals(45L, p[EcoLogPayload.KEY_ECO_FIX])
        assertEquals(40L, p[EcoLogPayload.KEY_ECO_STAND])
        assertEquals(15_000L, p[EcoLogPayload.KEY_ECO_GPS_MS])
        // Privacy-гейт DevLog: координаты в ключах запрещены — не бросает.
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("eco_fix"))
        // Без префикса полей нет — старый формат терпим.
        assertTrue(!json.contains("prefix_m"))
    }

    @Test
    fun `flush несет пер-эвентный префикс с источником`() {
        // fix-eco-signal-loss 4.2: один JSONL самодостаточен для медианы.
        val anchor = EcoLogPayload.flushPayload(
            batch = 2, rejected = 0, txnMs = "8.0", newCells = 5,
            mode = "eco", profile = "BURST",
            ecoFix = 10, ecoStand = 2, ecoGpsMs = 15_000,
            prefixM = 150L, prefixSrc = EcoLogPayload.PREFIX_SRC_ANCHOR
        )
        assertEquals(150L, anchor[EcoLogPayload.KEY_PREFIX_M])
        assertEquals("anchor", anchor[EcoLogPayload.KEY_PREFIX_SRC])
        val gap = EcoLogPayload.flushPayload(
            batch = 1, rejected = 0, txnMs = "6.0", newCells = 0,
            mode = "eco", profile = "BURST",
            ecoFix = 1, ecoStand = 0, ecoGpsMs = 30_000,
            prefixM = 3_000L, prefixSrc = EcoLogPayload.PREFIX_SRC_GAP
        )
        assertEquals("gap", gap[EcoLogPayload.KEY_PREFIX_SRC])
        val json = DevLog.buildPayloadJson(gap)
        assertTrue(json.contains("\"prefix_m\":3000"))
        assertTrue(json.contains("\"prefix_src\":\"gap\""))
    }

    @Test
    fun `no_fix несет фактический gap а не порог`() {
        // fix-eco-signal-loss 3.1: 12 минут тишины видно числом.
        val p = EcoLogPayload.noFixPayload(gapMs = 720_000L, mode = "eco", profile = "STANDBY")
        assertEquals(720_000L, p[EcoLogPayload.KEY_GAP_MS])
        val json = DevLog.buildPayloadJson(p)
        assertTrue(json.contains("\"gap_ms\":720000"))
    }

    @Test
    fun `источник спрямления — якорь или разрыв`() {
        // fix-eco-signal-loss 4.1: бюджет префикса не смешивается с разрывами.
        assertEquals("anchor", EcoLogPayload.prefixKind(true, 150.0, 0))
        assertNull("мелочь не считаем", EcoLogPayload.prefixKind(true, 40.0, 0))
        assertEquals("gap", EcoLogPayload.prefixKind(false, 3_000.0, 720))
        assertNull("межбатчевый шаг не разрыв", EcoLogPayload.prefixKind(false, 3_000.0, 30))
        assertNull(EcoLogPayload.prefixKind(false, 40.0, 720))
    }

    @Test
    fun `ключи без координат`() {
        val keys = listOf(
            EcoLogPayload.KEY_BATCH, EcoLogPayload.KEY_REJECTED,
            EcoLogPayload.KEY_TXN_MS, EcoLogPayload.KEY_NEW_CELLS,
            EcoLogPayload.KEY_MODE, EcoLogPayload.KEY_PROFILE,
            EcoLogPayload.KEY_ECO_FIX, EcoLogPayload.KEY_ECO_STAND,
            EcoLogPayload.KEY_ECO_GPS_MS, EcoLogPayload.KEY_FROM_PROFILE,
            EcoLogPayload.KEY_WAKE_M, EcoLogPayload.KEY_VERDICT,
            EcoLogPayload.KEY_PREFIX_M, EcoLogPayload.KEY_PREFIX_SRC,
            EcoLogPayload.KEY_GAP_MS
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
    fun `eco_state несет причину пробуждения`() {
        val p = EcoLogPayload.ecoStatePayload(
            mode = "eco", profile = "BURST",
            fromProfile = "STANDBY", wakeM = 150L, verdict = "MOVING"
        )
        assertEquals("STANDBY", p[EcoLogPayload.KEY_FROM_PROFILE])
        assertEquals(150L, p[EcoLogPayload.KEY_WAKE_M])
        assertEquals("MOVING", p[EcoLogPayload.KEY_VERDICT])
        DevLog.buildPayloadJson(p)
    }

    @Test
    fun `wake без якоря это -1 а не null`() {
        val p = EcoLogPayload.ecoStatePayload(
            mode = "eco", profile = "STANDBY",
            fromProfile = "BURST", wakeM = null, verdict = null
        )
        assertEquals(EcoLogPayload.NO_ANCHOR_M, p[EcoLogPayload.KEY_WAKE_M])
        assertEquals("?", p[EcoLogPayload.KEY_VERDICT])
    }

    @Test
    fun `eco_state несет источник пробуждения без координат`() {
        val p = EcoLogPayload.ecoStatePayload(
            mode = "eco", profile = "BURST",
            fromProfile = "STANDBY", wakeM = 150L, verdict = "WAKE",
            source = "motion"
        )
        assertEquals("motion", p[EcoLogPayload.KEY_SOURCE])
        DevLog.buildPayloadJson(p)
    }

    @Test
    fun `источник по умолчанию gps для старых вызовов`() {
        val p = EcoLogPayload.ecoStatePayload(
            mode = "eco", profile = "BURST",
            fromProfile = "STANDBY", wakeM = 150L, verdict = "MOVING"
        )
        assertEquals("gps", p[EcoLogPayload.KEY_SOURCE])
        val kl = EcoLogPayload.KEY_SOURCE.lowercase()
        assertTrue(!kl.contains("lat") && !kl.contains("lon"))
    }

    @Test
    fun `wifi и motion BURST несут окно 180с без координат`() {
        // fix-walk-fog-verdict 5.3.
        for (src in listOf("wifi", "motion")) {
            val p = EcoLogPayload.ecoStatePayload(
                mode = "eco", profile = "BURST",
                fromProfile = "STANDBY", wakeM = 150L, verdict = "WAKE",
                source = src
            )
            assertEquals(src, p[EcoLogPayload.KEY_SOURCE])
            assertEquals(180_000L, p[EcoLogPayload.KEY_BURST_WINDOW_MS])
            DevLog.buildPayloadJson(p)
        }
        val gps = EcoLogPayload.ecoStatePayload(
            mode = "eco", profile = "BURST",
            fromProfile = "STANDBY", wakeM = 150L, verdict = "WAKE",
            source = "gps"
        )
        assertNull(gps[EcoLogPayload.KEY_BURST_WINDOW_MS])
        val kl = EcoLogPayload.KEY_BURST_WINDOW_MS.lowercase()
        assertTrue(!kl.contains("lat") && !kl.contains("lon"))
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
}
