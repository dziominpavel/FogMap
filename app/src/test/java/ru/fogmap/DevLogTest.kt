package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.fogmap.diag.DevLog
import ru.fogmap.diag.DevRenderStats

class DevLogTest {

    @Before
    fun setup() {
        DevLog.wallClock = { 1_700_000_000_000L }
        DevLog.monoClock = { 10_000L }
        DevLog.fileSink = null
        DevLog.enabled = true
        DevLog.clearBuffer()
    }

    @Test
    fun `seq строго растет внутри сессии`() {
        val before = DevLog.snapshot().size
        DevLog.d("CAMERA", "tick")
        DevLog.d("CAMERA", "tick")
        DevLog.d("RENDER", "tick")
        val lines = DevLog.snapshot().drop(before).takeLast(3)
        assertEquals(3, lines.size)
        val seqs = lines.map { DevLog.parse(it)!!.seq }
        assertTrue(seqs[0] < seqs[1] && seqs[1] < seqs[2])
        val sessions = lines.map { DevLog.parse(it)!!.session }.toSet()
        assertEquals(1, sessions.size)
    }

    @Test
    fun `формат строки парсится split с лимитом`() {
        DevLog.i("RENDER", "agg", mapOf("cells" to 10, "holes" to 3, "dt_total_ms" to 42.5))
        val line = DevLog.snapshot().last()
        // 8 полей: разбор одним split с лимитом 8 (7 разделителей).
        val parts = line.split('|', limit = 8)
        assertEquals(8, parts.size)
        val e = DevLog.parse(line)
        assertNotNull(e)
        assertEquals("RENDER", e!!.tag)
        assertEquals(DevLog.Level.I, e.level)
        assertEquals("agg", e.msg)
        assertTrue(e.payloadJson.contains("\"cells\":10"))
    }

    @Test
    fun `агрегат различает fallback и несет уровень пятен`() {
        // Один упорядоченный поток: синглтон DevRenderStats делит окно между
        // тестами, поэтому все кадры — здесь с растущим nowMono.
        DevRenderStats.onFrame(
            dtMergeMs = 1.0, dtProjMs = 0.5, dtTotalMs = 1.5,
            cells = 100, holes = 20, nullProj = 0,
            overBudget = false, fallback = true, presenceZ = 19, nowMono = 10_000L
        )
        DevRenderStats.onFrame(
            dtMergeMs = 1.0, dtProjMs = 0.5, dtTotalMs = 1.5,
            cells = 100, holes = 20, nullProj = 0,
            overBudget = false, fallback = true, presenceZ = 19, nowMono = 12_500L
        )
        val line = DevLog.snapshot().last()
        val e = DevLog.parse(line)
        assertNotNull(e)
        assertEquals("agg", e!!.msg)
        assertTrue(e.payloadJson.contains("\"fallback_hits\":2"))
        assertTrue(e.payloadJson.contains("\"over_budget_hits\":0"))
        assertTrue(e.payloadJson.contains("\"presence_z\":19"))
    }

    @Test
    fun `payload плоский и экранирует разделители`() {
        DevLog.w("UI", "tap|punch\nline", mapOf("to" to "a|b"))
        val line = DevLog.snapshot().last()
        // Строка без переносов и с ровно 7 разделителями верхнего уровня.
        assertTrue(!line.contains('\n'))
        assertNotNull(DevLog.parse(line))
        assertTrue(DevLog.parse(line)!!.payloadJson.contains("a/b"))
    }
}
