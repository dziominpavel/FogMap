package ru.fogmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.ui.NotifPermission

/**
 * fix-track-gap-visibility 5.5: `POST_NOTIFICATIONS` спрашивается один раз и
 * только там, где системный диалог существует (API 33+). Отказ не мешает
 * записи, повторно не спрашиваем.
 */
class NotifPermissionTest {

    @Test
    fun `до API 33 запрос не нужен`() {
        assertFalse(NotifPermission.needsAsk(32, granted = false))
        assertFalse(NotifPermission.shouldAsk(32, granted = false, askedBefore = false))
        // Разрешение уже есть — спрашивать нечего.
        assertFalse(NotifPermission.shouldAsk(33, granted = true, askedBefore = false))
    }

    @Test
    fun `на API 33 без разрешения спрашиваем один раз`() {
        assertTrue(NotifPermission.needsAsk(33, granted = false))
        assertTrue(NotifPermission.shouldAsk(33, granted = false, askedBefore = false))
        assertFalse(
            "повторный запрос после отказа запрещен",
            NotifPermission.shouldAsk(33, granted = false, askedBefore = true)
        )
    }

    @Test
    fun `будущие API считаются как 33`() {
        assertTrue(NotifPermission.shouldAsk(36, granted = false, askedBefore = false))
    }
}
