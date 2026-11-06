package ru.fogmap.tracking

/**
 * Гейт лога счётчиков веток вердикта (fix-track-gap-visibility 4.1).
 *
 * Счётчики дня — материализованные инкременты: значения меняются тогда и только
 * тогда, когда пачка не пуста. Пустой тик (все нули) значений не меняет, значит
 * и события в логе быть не должно — именно эти повторы дали 58 % файла 21.10.
 * Полнота `counters.json` не зависит от гейта: `recordBranches` с нулями
 * вызывается на каждом тике, включая starved.
 *
 * Field-hardening 1.2 (06.11): даже непустые пачки писали 28,7 % файла
 * (`day_branches` 450 из 1 569 событий). Теперь событие — не чаще раза в
 * [MIN_LOG_INTERVAL_MS] и только сведённым итогом за интервал: счётчики в БД
 * по-прежнему пишутся на каждом flush, лог не теряет ни одного значения, но
 * перестаёт быть шумом.
 */
object BranchLogGate {

    /** Минимальный интервал между событиями `day_branches` (field-hardening 1.2). */
    const val MIN_LOG_INTERVAL_MS = 5 * 60_000L

    fun changed(batchCounts: Map<String, Long>): Boolean =
        batchCounts.values.any { it != 0L }

    /** Полный снимок веток с нулями — та же схема, что в `counters.json`. */
    fun snapshot(batchCounts: Map<String, Long>): Map<String, Long> =
        TrustEngine.BRANCH_KEYS.associateWith { batchCounts[it] ?: 0L }

    /**
     * Пора ли писать событие: есть что писать И прошёл интервал с прошлого
     * события. Первое событие в процессе пишется сразу (нет базы времени).
     */
    fun shouldLog(lastLoggedAtMs: Long, nowMs: Long, pending: Map<String, Long>): Boolean =
        changed(pending) && (lastLoggedAtMs <= 0L || nowMs - lastLoggedAtMs >= MIN_LOG_INTERVAL_MS)

    /** Свести пачку в накопитель между событиями. */
    fun merge(acc: MutableMap<String, Long>, batch: Map<String, Long>) {
        for ((key, value) in batch) {
            if (value == 0L && !acc.containsKey(key)) continue
            acc[key] = (acc[key] ?: 0L) + value
        }
    }
}
