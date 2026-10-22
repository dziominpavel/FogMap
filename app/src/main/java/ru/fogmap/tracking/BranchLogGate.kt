package ru.fogmap.tracking

/**
 * Гейт лога счётчиков веток вердикта (fix-track-gap-visibility 4.1).
 *
 * Счётчики дня — материализованные инкременты: значения меняются тогда и только
 * тогда, когда пачка не пуста. Пустой тик (все нули) значений не меняет, значит
 * и события в логе быть не должно — именно эти повторы дали 58 % файла 21.10.
 * Полнота `counters.json` не зависит от гейта: `recordBranches` с нулями
 * вызывается на каждом тике, включая starved.
 */
object BranchLogGate {

    fun changed(batchCounts: Map<String, Long>): Boolean =
        batchCounts.values.any { it != 0L }

    /** Полный снимок веток с нулями — та же схема, что в `counters.json`. */
    fun snapshot(batchCounts: Map<String, Long>): Map<String, Long> =
        TrustEngine.BRANCH_KEYS.associateWith { batchCounts[it] ?: 0L }
}
