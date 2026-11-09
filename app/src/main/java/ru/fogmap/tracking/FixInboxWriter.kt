package ru.fogmap.tracking

import ru.fogmap.data.db.AppDatabase

/**
 * Запись пачки фиксов в журнал (fix-journal 1.2).
 *
 * Один INSERT OR IGNORE на пачку: уникальный ключ отсекает повторную доставку
 * того же фикса, а разобранный хвост чистится, чтобы журнал не рос бесконечно.
 */
object FixInboxWriter {

    /** Сколько хранить разобранные строки: два дня хватает для разбора инцидента. */
    const val PRUNE_AFTER_MS = 2L * 24 * 60 * 60 * 1000

    suspend fun write(db: AppDatabase, envelopes: List<FixEnvelope>) {
        if (envelopes.isEmpty()) return
        db.fixInboxDao().insertAllIgnore(envelopes.map { it.toEntity() })
    }

    suspend fun prune(db: AppDatabase, nowMs: Long = System.currentTimeMillis()) {
        db.fixInboxDao().pruneDone(nowMs - PRUNE_AFTER_MS)
    }
}
