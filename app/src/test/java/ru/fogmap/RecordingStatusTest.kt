package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.tracking.RecordingStatus
import ru.fogmap.tracking.RecordingStatus.State

/**
 * change recording-mode, задача 7.1: состояние записи для владельца.
 * «Служба запущена» больше не равно «пишем»: разница видна по возрасту фикса.
 */
class RecordingStatusTest {

    private val now = 1_000_000_000L

    @Test
    fun `выключенная запись — OFF`() {
        val s = RecordingStatus.of(recording = false, sinceMs = now - 5_000L, nowMs = now)
        assertEquals(State.OFF, s.state)
        assertEquals("Запись выключена", RecordingStatus.label(s))
    }

    @Test
    fun `свежий фикс — LIVE с возрастом`() {
        val s = RecordingStatus.of(recording = true, sinceMs = now - 12_000L, nowMs = now)
        assertEquals(State.LIVE, s.state)
        assertEquals("Пишем · 12 с назад", RecordingStatus.label(s))
    }

    @Test
    fun `тишина дольше двух минут — STALE`() {
        val s = RecordingStatus.of(recording = true, sinceMs = now - 240_000L, nowMs = now)
        assertEquals(State.STALE, s.state)
        assertEquals("Нет фиксов 4 мин", RecordingStatus.label(s))
    }

    @Test
    fun `возраст форматируется по величине`() {
        assertEquals("0 с", RecordingStatus.shortAge(0))
        assertEquals("45 с", RecordingStatus.shortAge(45_000))
        assertEquals("3 мин", RecordingStatus.shortAge(190_000))
        assertEquals("2 ч", RecordingStatus.shortAge(7_300_000))
    }

    @Test
    fun `запись включена, фиксов не было — LIVE до порога`() {
        val s = RecordingStatus.of(recording = true, sinceMs = 0L, nowMs = now)
        assertEquals(State.LIVE, s.state)
    }
}
