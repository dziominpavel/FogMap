package ru.fogmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.RecordingMigration

/**
 * change recording-mode, задача 1.1: миграция тумблера с инвертированного
 * «Пауза» на прямой «Запись». Ошибка в любую сторону видна владельцу сразу:
 * либо пустая поездка, либо запись против его воли.
 */
class RecordingMigrationTest {

    @Test
    fun `новый ключ есть — значение не трогаем`() {
        assertTrue(RecordingMigration.resolve(enabled = true, legacyPaused = true))
        assertFalse(RecordingMigration.resolve(enabled = false, legacyPaused = false))
    }

    @Test
    fun `пауза была выключена — запись включена`() {
        assertTrue(RecordingMigration.resolve(enabled = null, legacyPaused = false))
    }

    @Test
    fun `пауза была включена — запись выключена`() {
        assertFalse(RecordingMigration.resolve(enabled = null, legacyPaused = true))
    }

    @Test
    fun `чистая установка пишет по умолчанию`() {
        assertTrue(RecordingMigration.resolve(enabled = null, legacyPaused = null))
    }
}
