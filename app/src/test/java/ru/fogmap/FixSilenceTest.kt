package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.tracking.FixSilence
import ru.fogmap.tracking.FixSilence.Action

/**
 * change recording-mode, задача 4.1: неисправность определяется возрастом
 * последнего фикса, а не фактом запуска службы. Именно этого не хватало в
 * полевые сутки: сервис был жив, а фиксов не было часами.
 */
class FixSilenceTest {

    private val now = 1_000_000_000L

    @Test
    fun `свежий фикс — действий нет`() {
        val d = FixSilence.decide(now, now - 8_000L, recording = true, failures = 0, lastRestartMs = 0L)
        assertEquals(Action.NONE, d.action)
    }

    @Test
    fun `выключенная запись не лечится`() {
        val d = FixSilence.decide(now, now - 600_000L, recording = false, failures = 0, lastRestartMs = 0L)
        assertEquals(Action.NONE, d.action)
    }

    @Test
    fun `через две минуты тишины — переподписка`() {
        val d = FixSilence.decide(now, now - 121_000L, recording = true, failures = 0, lastRestartMs = 0L)
        assertEquals(Action.RESUBSCRIBE, d.action)
        assertEquals(1, d.failures)
    }

    @Test
    fun `три переподписки подряд — перезапуск службы`() {
        val d = FixSilence.decide(now, now - 600_000L, recording = true, failures = 3, lastRestartMs = 0L)
        assertEquals(Action.RESTART, d.action)
        assertEquals(0, d.failures)
    }

    @Test
    fun `перезапуск не чаще кулдауна`() {
        val recent = FixSilence.decide(
            now, now - 600_000L, recording = true, failures = 3, lastRestartMs = now - 30_000L
        )
        assertEquals(Action.GIVE_UP, recent.action)
        val later = FixSilence.decide(
            now, now - 600_000L, recording = true, failures = 3,
            lastRestartMs = now - FixSilence.RESTART_COOLDOWN_MS - 1
        )
        assertEquals(Action.RESTART, later.action)
    }

    @Test
    fun `служба только что стартовала — ждём`() {
        val d = FixSilence.decide(now, now - 5_000L, recording = true, failures = 0, lastRestartMs = 0L)
        assertEquals(Action.NONE, d.action)
    }
}
