package ru.fogmap.diag

/**
 * Пороги PERF-событий (fix-track-gap-visibility 4.3). Отдельный объект без
 * Android: `DevPerfMonitor` при инициализации трогает Looper, поэтому чистая
 * классификация живет здесь и тестируется на JVM.
 */
object PerfThresholds {

    /** Задержка кадра до этого порога не значима. */
    const val JANK_MS = 700L

    /**
     * Отсутствие отклика дольше минуты — это остановка процесса, а не задержка
     * кадра: отдельное событие `PERF/stall`.
     */
    const val STALL_MS = 60_000L

    /** Тип события по длительности паузы: null — ниже порога значимости. */
    fun eventFor(gapMs: Long): String? = when {
        gapMs >= STALL_MS -> "stall"
        gapMs > JANK_MS -> "jank"
        else -> null
    }
}
