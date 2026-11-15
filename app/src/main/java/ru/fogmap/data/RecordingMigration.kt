package ru.fogmap.data

/**
 * Миграция тумблера записи (change recording-mode, D2).
 *
 * До изменения запись управлялась инвертированным тумблером «Пауза»:
 * `paused = false` означало «пишем», `paused = true` — «стоим». Новый ключ
 * `recording_enabled` прямой. Чистая функция: на вход значения обоих ключей,
 * на выход — что записать в `recording_enabled`.
 *
 * Чистая, чтобы миграцию можно было проверить на JVM без DataStore: ошибка
 * здесь означает либо пустую запись у владельца, либо запись против его воли.
 */
object RecordingMigration {

    /**
     * @param enabled текущее значение `recording_enabled` (null — ключа нет)
     * @param legacyPaused старое значение `paused` (null — ключа не было)
     * @return значение, которое нужно записать в `recording_enabled`
     */
    fun resolve(enabled: Boolean?, legacyPaused: Boolean?): Boolean = when {
        // Ключ уже есть — миграция не нужна, значение не трогаем.
        enabled != null -> enabled
        // Переход со старой версии: пауза была выключена — значит, писали.
        legacyPaused != null -> !legacyPaused
        // Чистая установка: запись включена (иначе первый же выезд пустой).
        else -> true
    }
}
