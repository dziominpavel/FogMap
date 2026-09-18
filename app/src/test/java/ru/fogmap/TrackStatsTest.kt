package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.tracking.LocationFilter

class TrackStatsTest {
    private fun pt(lat: Double, lon: Double = 37.0, time: Long = 0L) =
        RawPoint(time = time, lat = lat, lon = lon, acc = 10f, speed = 1f)

    @Test
    fun `батч из N точек дает дистанцию гаверсинуса`() {
        // 0.001° широты ≈ 111 м; 3 точки = 2 отрезка ≈ 222 м.
        val points = listOf(pt(55.0, time = 0), pt(55.001, time = 8000), pt(55.002, time = 16000))
        val d = FogRepository.batchDistance(points)
        assertTrue("ожидалось ~222 м, получено $d", d > 220 && d < 225)
    }

    @Test
    fun `хвост прошлого батча входит в дистанцию`() {
        val prev = pt(55.0)
        val points = listOf(pt(55.001, time = 8000))
        val d = FogRepository.batchDistance(points, prev)
        assertTrue("ожидалось ~111 м, получено $d", d > 110 && d < 113)
    }

    @Test
    fun `отрезки с недоверенными концами не раздувают дистанцию`() {
        val jump = pt(55.00225, time = 8000).copy(trust = 10) // выброс 250м
        val back = pt(55.0, time = 16000)
        val points = listOf(pt(55.0, time = 0), jump, back)
        val d = FogRepository.batchDistance(points)
        // Отрезки туда (250м) и обратно (250м) пропущены — честный ноль.
        assertEquals(0.0, d, 1e-9)
    }

    @Test
    fun `недоверенный хвост не линкуется`() {
        val prev = pt(55.0).copy(trust = 10)
        val d = FogRepository.batchDistance(listOf(pt(55.001, time = 8000)), prev)
        assertEquals(0.0, d, 1e-9)
    }

    @Test
    fun `одна точка без хвоста — ноль дистанции`() {
        assertEquals(0.0, FogRepository.batchDistance(listOf(pt(55.0))), 1e-9)
    }

    @Test
    fun `пустой батч — ноль дистанции`() {
        assertEquals(0.0, FogRepository.batchDistance(emptyList(), pt(55.0)), 1e-9)
    }

    @Test
    fun `ключи счетчиков отбросов по схеме rejected-reason-suffix`() {
        val keys = FogRepository.rejectedKeys(
            mapOf("accuracy" to 3, "speed" to 0, "mock" to 1),
            listOf("all", "day_2026-09-17")
        ).toSet()
        assertTrue(keys.contains("rejected_accuracy_all" to 3L))
        assertTrue(keys.contains("rejected_accuracy_day_2026-09-17" to 3L))
        assertTrue(keys.contains("rejected_mock_all" to 1L))
        assertTrue(keys.none { it.first.startsWith("rejected_speed") })
    }

    @Test
    fun `ключи фильтра покрываются словарем причин`() {
        val filterKeys = LocationFilter.Reason.entries
            .filter { it != LocationFilter.Reason.OK }
            .map { it.key }
        assertTrue(FogRepository.REJECT_REASONS.containsAll(filterKeys))
    }
}
