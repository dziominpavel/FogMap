package ru.fogmap.tracking

/**
 * Состав диагностических payload (change recording-mode, dev-logging).
 *
 * Один JSONL самодостаточен для сверки записи: flush несет счётчики пачки,
 * no_fix — фактическую длительность тишины, day_stats — дневные счётчики.
 * Эко-полей (профиль, префикс, переходы) больше нет: режим один.
 * Координат нет по построению: только счетчики, метры и имена.
 */
object EcoLogPayload {
    const val KEY_BATCH = "batch"
    const val KEY_REJECTED = "rejected"
    const val KEY_TXN_MS = "txn_ms"
    const val KEY_NEW_CELLS = "new_cells"
    const val KEY_MODE = "mode"
    const val KEY_ECO_FIX = "eco_fix"
    const val KEY_ECO_STAND = "eco_stand"
    const val KEY_ECO_GPS_MS = "eco_gps_ms"
    /** Фактическая длительность тишины Fused. */
    const val KEY_GAP_MS = "gap_ms"
    /** Время с прошлого тика записи (fix-track-gap-visibility 4.2), мс. */
    const val KEY_SINCE_LAST_MS = "since_last_ms"
    /** Абсолютная монотонная шкала загрузки (fix-track-gap-visibility 4.2). */
    const val KEY_BOOT_MONO_MS = "boot_mono_ms"
    /** Фиксы, пришедшие отложенной заморозкой пачкой (field-hardening 1.3). */
    const val KEY_STALE_REPLAY = "stale_replay"
    /** Дневные счётчики (fix-track-gap-visibility 4.4). */
    const val KEY_DATE = "date"
    const val KEY_DIST_CM = "dist_cm"
    const val KEY_TIME_S = "time_s"
    const val KEY_POINTS = "points"
    const val KEY_AREA_CELLS = "area_cells"
    const val KEY_FLUSH_COUNT = "flush"
    const val KEY_VIA = "via"
    const val REJ_PREFIX = "rej_"

    fun flushPayload(
        batch: Int,
        rejected: Long,
        txnMs: String,
        newCells: Int,
        mode: String,
        ecoFix: Long,
        ecoStand: Long,
        ecoGpsMs: Long,
        sinceLastMs: Long? = null,
        bootMonoMs: Long? = null,
        staleReplay: Long? = null
    ): Map<String, Any?> = mapOf(
        KEY_BATCH to batch,
        KEY_REJECTED to rejected,
        KEY_TXN_MS to txnMs,
        KEY_NEW_CELLS to newCells,
        KEY_MODE to mode,
        KEY_ECO_FIX to ecoFix,
        KEY_ECO_STAND to ecoStand,
        KEY_ECO_GPS_MS to ecoGpsMs,
        KEY_SINCE_LAST_MS to sinceLastMs,
        KEY_BOOT_MONO_MS to bootMonoMs,
        KEY_STALE_REPLAY to staleReplay
    )

    /**
     * Дневные счётчики (fix-track-gap-visibility 4.4): дистанция/время/площадь/
     * flush/отбросы — из таблицы counters (тот же ключ, что `counters.json`),
     * точки — из строки дня. По логу за неделю видно, какие дни были пустыми.
     */
    fun dayStatsPayload(
        date: String,
        distCm: Long,
        timeS: Long,
        points: Int,
        areaCells: Long,
        flush: Long,
        rejected: Map<String, Long>,
        via: String? = null
    ): Map<String, Any?> = buildMap {
        put(KEY_DATE, date)
        put(KEY_DIST_CM, distCm)
        put(KEY_TIME_S, timeS)
        put(KEY_POINTS, points)
        put(KEY_AREA_CELLS, areaCells)
        put(KEY_FLUSH_COUNT, flush)
        for ((reason, n) in rejected) put(REJ_PREFIX + reason.replace('-', '_'), n)
        if (via != null) put(KEY_VIA, via)
    }

    /** Тишина Fused: фактическая длительность интервала, а не порог. */
    fun noFixPayload(gapMs: Long, mode: String): Map<String, Any?> = mapOf(
        KEY_GAP_MS to gapMs,
        KEY_MODE to mode
    )

    /**
     * Счётчики веток TrustEngine (tracking-reliability): все ключи видимы,
     * включая нули — кандидаты на удаление правил, не «нет данных».
     */
    fun branchPayload(counts: Map<String, Long>): Map<String, Any?> =
        TrustEngine.BRANCH_KEYS.associateWith { counts[it] ?: 0L }
}
