package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.tracking.LocationFilter
import ru.fogmap.tracking.RawTrace
import ru.fogmap.tracking.TrustEngine

class RawTraceTest {
    private fun hist(time: Long, lat: Double) =
        TrustEngine.HistPoint(time = time, lat = lat, lon = 37.0, acc = 10f)

    @Test
    fun `stand fix сохраняется в raw а не дропается`() {
        // Сервис дропает STAND из track_points, но raw обязан его хранить.
        val row = RawTrace.build(
            time = 1_000L, lat = 55.0, lon = 37.0, acc = 10f, speed = 0f,
            isMock = false, filter = LocationFilter.Reason.OK.key,
            state = TrustEngine.State.STAND.name, trust = TrustEngine.TRUST_STAND,
            openFog = 0, rejectReason = null,
            history = listOf(hist(0L, 55.0)), prev = null
        )
        assertEquals(LocationFilter.Reason.OK.key, row.filter)
        assertEquals(TrustEngine.State.STAND.name, row.state)
    }

    @Test
    fun `accuracy отброс хранит причину без вердикта`() {
        val row = RawTrace.build(
            time = 1_000L, lat = 55.0, lon = 37.0, acc = 60f, speed = null,
            isMock = false, filter = FogRepository.REJECT_ACCURACY,
            state = null, trust = null, openFog = null, rejectReason = null,
            history = emptyList(), prev = null
        )
        assertEquals(FogRepository.REJECT_ACCURACY, row.filter)
        assertNull(row.state)
        assertNull(row.trust)
    }

    @Test
    fun `jump трейсится с implied cap и teleport`() {
        // Ровная серия ~14 м/с, затем скачок 250 м за 8 сек.
        val history = listOf(
            hist(0L, 55.0), hist(8_000L, 55.001),
            hist(16_000L, 55.002), hist(24_000L, 55.003)
        )
        val row = RawTrace.build(
            time = 32_000L, lat = 55.00525, lon = 37.0, acc = 10f, speed = 30f,
            isMock = false, filter = LocationFilter.Reason.OK.key,
            state = TrustEngine.State.SUSPECT.name, trust = TrustEngine.TRUST_SUSPECT,
            openFog = 0, rejectReason = FogRepository.REJECT_JUMP,
            history = history, prev = null
        )
        assertEquals(FogRepository.REJECT_JUMP, row.rejectReason)
        val implied = row.implied ?: -1.0
        assertTrue("ожидалось implied ~31, получено $implied", implied > 30 && implied < 33)
        assertEquals(TrustEngine.STAND_CAP_MS, row.cap ?: -1.0, 1e-9)
        assertEquals(0, row.teleport)
    }

    @Test
    fun `телепорт флаг при сдвиге больше 500м`() {
        val history = listOf(hist(0L, 55.0))
        val row = RawTrace.build(
            time = 8_000L, lat = 55.01, lon = 37.0, acc = 10f, speed = null,
            isMock = false, filter = LocationFilter.Reason.OK.key,
            state = TrustEngine.State.SUSPECT.name, trust = TrustEngine.TRUST_SUSPECT,
            openFog = 0, rejectReason = FogRepository.REJECT_JUMP,
            history = history, prev = null
        )
        assertEquals(1, row.teleport)
    }
}
