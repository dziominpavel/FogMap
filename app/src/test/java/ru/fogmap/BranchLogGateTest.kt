package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.BranchLogGate
import ru.fogmap.tracking.TrustEngine

/**
 * fix-track-gap-visibility 4.1: `TRACK/day_branches` пишется только когда
 * счётчики дня изменились. Пустой тик (все нули) — 58 % файла 21.10 — молчит;
 * полнота `counters.json` не страдает (нули материализуются в БД отдельно).
 */
class BranchLogGateTest {

    @Test
    fun `пустая пачка не пишется`() {
        assertFalse(BranchLogGate.changed(emptyMap()))
        // Снимок всех веток с нулями — тоже «значений не изменилось».
        assertFalse(BranchLogGate.changed(BranchLogGate.snapshot(emptyMap())))
    }

    @Test
    fun `повторные тики без изменений не дают событий`() {
        // Два подряд пустых тика: раньше это были две строки day_branches,
        // теперь — ни одной (значения счетчиков одинаковы).
        val quiet = BranchLogGate.snapshot(emptyMap())
        assertFalse(BranchLogGate.changed(quiet))
        assertFalse(BranchLogGate.changed(quiet))
    }

    @Test
    fun `изменение счётчиков пишется`() {
        assertTrue(BranchLogGate.changed(mapOf("static" to 1L)))
        assertTrue(BranchLogGate.changed(mapOf("kind_WALK" to 3L, "wake" to 0L)))
    }

    @Test
    fun `снимок содержит все ветки включая нули`() {
        val snap = BranchLogGate.snapshot(mapOf("static" to 2L))
        assertEquals(TrustEngine.BRANCH_KEYS.size, snap.size)
        assertEquals(2L, snap["static"])
        assertEquals(0L, snap["teleport"])
        assertTrue(TrustEngine.BRANCH_KEYS.all { snap.containsKey(it) })
    }
}
