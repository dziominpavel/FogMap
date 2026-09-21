package ru.fogmap

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `ключи без координат`() {
        val keys = listOf(
            EcoLogPayload.KEY_BATCH, EcoLogPayload.KEY_REJECTED,
            EcoLogPayload.KEY_TXN_MS, EcoLogPayload.KEY_NEW_CELLS,
            EcoLogPayload.KEY_MODE, EcoLogPayload.KEY_PROFILE,
            EcoLogPayload.KEY_ECO_FIX, EcoLogPayload.KEY_ECO_STAND,
            EcoLogPayload.KEY_ECO_GPS_MS, EcoLogPayload.KEY_FROM_PROFILE,
            EcoLogPayload.KEY_WAKE_M, EcoLogPayload.KEY_VERDICT
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
}
