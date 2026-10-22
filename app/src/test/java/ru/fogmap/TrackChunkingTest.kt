package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.TrackChunking

/**
 * fix-track-gap-visibility 3.1–3.3: честный `time_s`.
 *
 * Время дня — сумма непрерывных отрезков (до 120 с), а не «последняя точка
 * минус первая»: разрыв доставки в 40 минут больше не становится временем
 * движения (поле 10.04: 18 647 с при 325 м).
 */
class TrackChunkingTest {

    private fun times(count: Int, stepMs: Long, startMs: Long = 0L): List<Long> =
        (0 until count).map { startMs + it * stepMs }

    @Test
    fun `непрерывная серия — один отрезок и спан по последней точке`() {
        val t = times(10, 8_000L)
        assertEquals(listOf(0), TrackChunking.starts(t))
        assertEquals(72_000L, TrackChunking.totalSpanMs(t))
    }

    @Test
    fun `дыра сорок минут не входит во время движения`() {
        // 3 точки, дыра 40 минут, еще 3 точки: в time_s попадут две серии по 16 с.
        val t = listOf(0L, 8_000L, 16_000L) + listOf(40 * 60_000L + 0L, 40 * 60_000L + 8_000L, 40 * 60_000L + 16_000L)
        val chunks = TrackChunking.starts(t)
        assertEquals(listOf(0, 3), chunks)
        assertEquals(32_000L, TrackChunking.totalSpanMs(t))
        assertTrue("время не должно впитывать дыру", TrackChunking.totalSpanMs(t) < 60_000L)
    }

    @Test
    fun `граница спана 120 секунд режет отрезок`() {
        // Ровно 120 000 — еще тот же отрезок, 120 001 — уже новый.
        val exact = listOf(0L, 120_000L)
        assertEquals(1, TrackChunking.starts(exact).size)
        val over = listOf(0L, 120_001L)
        assertEquals(2, TrackChunking.starts(over).size)
        assertEquals(0L, TrackChunking.totalSpanMs(over))
    }

    @Test
    fun `предел точек режет отрезок`() {
        val t = times(TrackChunking.MAX_COUNT + 5, 1_000L)
        val s = TrackChunking.starts(t)
        assertEquals(listOf(0, TrackChunking.MAX_COUNT), s)
        // 19 секунд в первом отрезке + 4 секунды во втором.
        assertEquals(19_000L + 4_000L, TrackChunking.totalSpanMs(t))
    }

    /**
     * 3.2: live и rebuild считают `time_s` одним правилом. Rebuild подает
     * принятые точки через `chunkAccepted`, живая запись — через
     * `totalSpanMs`; суммы обязаны совпасть на одном наборе.
     */
    @Test
    fun `сумма по отрезкам совпадает с общим спаном — live и rebuild сходятся`() {
        val t = times(20, 8_000L) + times(5, 8_000L, startMs = 20 * 8_000L + 3 * 3_600_000L)
        val starts = TrackChunking.starts(t)
        val byChunks = starts.indices.sumOf { idx ->
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else t.size
            t[to - 1] - t[from]
        }
        assertEquals(byChunks, TrackChunking.totalSpanMs(t))
        // День с двумя сессиями: 120 с + 24 с + 32 с, а не 3 часа с лишним.
        assertEquals(176_000L, TrackChunking.totalSpanMs(t))
        assertTrue("время не должно впитывать дыру", TrackChunking.totalSpanMs(t) < 3_600_000L)
    }

    @Test
    fun `инверсия времени после разморозки не вычитается из суммы`() {
        val t = listOf(10_000L, 5_000L)
        assertEquals(0L, TrackChunking.totalSpanMs(t))
    }

    @Test
    fun `пустой список дает ноль`() {
        assertTrue(TrackChunking.starts(emptyList()).isEmpty())
        assertEquals(0L, TrackChunking.totalSpanMs(emptyList()))
        assertFalse(TrackChunking.totalSpanMs(emptyList()) > 0L)
    }
}
