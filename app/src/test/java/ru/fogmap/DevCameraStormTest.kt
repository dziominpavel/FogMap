package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.fogmap.diag.DevCameraStats
import ru.fogmap.diag.DevLog
import java.util.Locale

/**
 * ВРЕМЕННОЕ (no-tilt-plus-diag): порог шторма камеры и локале-независимый формат чисел.
 * Один метод — синглтон DevCameraStats хранит окно между вызовами, порядок фаз важен.
 */
class DevCameraStormTest {

    @Before
    fun setup() {
        DevLog.wallClock = { 1_700_000_000_000L }
        DevLog.monoClock = { 10_000L }
        DevLog.fileSink = null
        DevLog.enabled = true
        DevLog.clearBuffer()
    }

    @Test
    fun `шторм детектируется один раз за окно и числа с точкой при русской локали`() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale("ru", "RU"))
        try {
            // Фаза 1: спокойный жест 2 события/сек — шторма нет, окно сбрасывается.
            val t1 = 1_000_000L
            repeat(5) { i -> DevCameraStats.onEvent(15f, 0f, 0f, false, t1 + i * 500L) }
            val calmStorms = DevLog.snapshot().mapNotNull { DevLog.parse(it) }
                .count { it.tag == "CAMERA" && it.msg == "storm" }
            assertEquals(0, calmStorms)

            // Фаза 2: 100 событий/сек — шторм обязан сработать до конца 2-секундного окна.
            val t2 = t1 + 100_000L
            repeat(120) { i -> DevCameraStats.onEvent(18f, 68f, 50f, false, t2 + i * 10L) }
            val events = DevLog.snapshot().mapNotNull { DevLog.parse(it) }
            val storms = events.filter { it.tag == "CAMERA" && it.msg == "storm" }
            assertEquals(1, storms.size)
            assertEquals(DevLog.Level.W, storms[0].level)
            // Точка, а не запятая: events_per_s вида "100.0", не "100,0".
            assertTrue(
                storms[0].payloadJson.contains(Regex("\"events_per_s\":\"\\d+\\.\\d+\""))
            )
            // Счетчик исходящих move() присутствует в агрегате.
            val aggs = events.filter { it.tag == "CAMERA" && it.msg == "agg" }
            assertTrue(aggs.isNotEmpty())
            assertTrue(aggs.last().payloadJson.contains("\"moves\":"))
        } finally {
            Locale.setDefault(default)
        }
    }
}
