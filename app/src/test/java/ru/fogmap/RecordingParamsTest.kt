package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.tracking.RecordingParams

/**
 * change recording-mode, задача 2.1: один профиль опроса на всю запись.
 * Ступеней STANDBY/BURST больше нет — любой «спящий» интервал здесь означал бы
 * возврат к тому, из-за чего поездка теряла начало.
 */
class RecordingParamsTest {

    @Test
    fun `параметры подписки — пять секунд, без дистанционного фильтра`() {
        val p = RecordingParams.fused()
        assertEquals(5_000L, p.intervalMs)
        assertEquals(3_000L, p.minIntervalMs)
        assertEquals(0f, p.distanceM, 0f)
    }

    @Test
    fun `приоритет — высокая точность`() {
        assertEquals(100, RecordingParams.fused().priority)
    }

    @Test
    fun `спящих интервалов не осталось`() {
        // Любой интервал длиннее 10 секунд означал бы возврат эко-ступеней.
        assertEquals(true, RecordingParams.INTERVAL_MS <= 10_000L)
        assertEquals(true, RecordingParams.MIN_INTERVAL_MS <= RecordingParams.INTERVAL_MS)
    }
}
