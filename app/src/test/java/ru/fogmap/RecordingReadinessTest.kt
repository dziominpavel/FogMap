package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.RecordingReadiness
import ru.fogmap.tracking.RecordingReadiness.Item
import ru.fogmap.tracking.RecordingReadiness.Level

/**
 * change recording-mode, задача 7.1: чек-лист проверяет факт, а не запрос.
 * Блокируют только те пункты, без которых запись физически не пойдёт.
 */
class RecordingReadinessTest {

    @Test
    fun `всё выдано — включать можно`() {
        val e = RecordingReadiness.evaluate(true, true, true, true, true)
        assertTrue(RecordingReadiness.canEnable(e))
        assertTrue(e.all { it.level == Level.OK })
    }

    @Test
    fun `без точной геолокации включать нельзя`() {
        val e = RecordingReadiness.evaluate(false, true, true, true, true)
        assertFalse(RecordingReadiness.canEnable(e))
        assertEquals(listOf("Точная геолокация"), RecordingReadiness.blockerTitles(e))
    }

    @Test
    fun `без фоновой геолокации включать нельзя`() {
        val e = RecordingReadiness.evaluate(true, false, true, true, true)
        assertFalse(RecordingReadiness.canEnable(e))
        assertEquals(Item.BACKGROUND, e.first { it.level == Level.BLOCKER }.item)
    }

    @Test
    fun `уведомления и батарея — предупреждения, а не блок`() {
        val e = RecordingReadiness.evaluate(true, true, false, false, false)
        assertTrue(RecordingReadiness.canEnable(e))
        val warnings = e.filter { it.level == Level.WARNING }.map { it.item }
        assertEquals(listOf(Item.NOTIFICATIONS, Item.BATTERY, Item.AUTOSTART), warnings)
    }
}
