package ru.fogmap.tracking

/**
 * Состав диагностических payload (battery-eco-logging-gap, чистый объект).
 * Один JSONL самодостаточен для сверки сна/пробуждений с треком:
 * flush несет эко-снимок пачки, eco_state — причину перехода.
 * Координат нет по построению: только счетчики, метры и имена.
 */
object EcoLogPayload {
    const val KEY_BATCH = "batch"
    const val KEY_REJECTED = "rejected"
    const val KEY_TXN_MS = "txn_ms"
    const val KEY_NEW_CELLS = "new_cells"
    const val KEY_MODE = "mode"
    const val KEY_PROFILE = "profile"
    const val KEY_ECO_FIX = "eco_fix"
    const val KEY_ECO_STAND = "eco_stand"
    const val KEY_ECO_GPS_MS = "eco_gps_ms"
    const val KEY_FROM_PROFILE = "from_profile"
    const val KEY_WAKE_M = "wake_m"
    const val KEY_VERDICT = "verdict"
    /** Источник пробуждения (wake-balance-parking 4.1): gps/motion/wifi/timeout/restart. */
    const val KEY_SOURCE = "source"
    /**
     * Пер-эвентный префикс (fix-eco-signal-loss 4.1/4.2): длина и источник
     * (anchor — пробуждение от якоря, gap — разрыв после тишины).
     */
    const val KEY_PREFIX_M = "prefix_m"
    const val KEY_PREFIX_SRC = "prefix_src"
    const val PREFIX_SRC_ANCHOR = "anchor"
    const val PREFIX_SRC_GAP = "gap"
    /** Фактическая длительность тишины Fused (fix-eco-signal-loss 3.1). */
    const val KEY_GAP_MS = "gap_ms"
    /** Окно BURST wifi/motion, мс (fix-walk-fog-verdict 5.3: 180_000). */
    const val KEY_BURST_WINDOW_MS = "burst_window_ms"

    /** Нет якоря / неприменимо (wake_m): число, а не null — парсер проще. */
    const val NO_ANCHOR_M = -1L

    fun flushPayload(
        batch: Int,
        rejected: Long,
        txnMs: String,
        newCells: Int,
        mode: String,
        profile: String,
        ecoFix: Long,
        ecoStand: Long,
        ecoGpsMs: Long,
        prefixM: Long? = null,
        prefixSrc: String? = null
    ): Map<String, Any?> = mapOf(
        KEY_BATCH to batch,
        KEY_REJECTED to rejected,
        KEY_TXN_MS to txnMs,
        KEY_NEW_CELLS to newCells,
        KEY_MODE to mode,
        KEY_PROFILE to profile,
        KEY_ECO_FIX to ecoFix,
        KEY_ECO_STAND to ecoStand,
        KEY_ECO_GPS_MS to ecoGpsMs,
        KEY_PREFIX_M to prefixM,
        KEY_PREFIX_SRC to prefixSrc
    )

    /** Тишина Fused (fix-eco-signal-loss 3.1): фактическая длительность, не порог. */
    fun noFixPayload(gapMs: Long, mode: String, profile: String): Map<String, Any?> = mapOf(
        KEY_GAP_MS to gapMs,
        KEY_MODE to mode,
        KEY_PROFILE to profile
    )

    /**
     * Источник спрямления (fix-eco-signal-loss 4.1): якорь пробуждения идет
     * в бюджет префикса, разрыв после тишины — в отдельную метрику, мелочь
     * (меньше 50 м или межбатчевые секунды) не считается вовсе. Чистая,
     * тестируется без Android.
     */
    fun prefixKind(hasAnchor: Boolean, distanceM: Double, gapS: Long): String? = when {
        hasAnchor && distanceM >= 50.0 -> PREFIX_SRC_ANCHOR
        !hasAnchor && distanceM >= 50.0 && gapS >= 60 -> PREFIX_SRC_GAP
        else -> null
    }

    fun ecoStatePayload(
        mode: String,
        profile: String,
        fromProfile: String,
        wakeM: Long?,
        verdict: String?,
        source: String? = null
    ): Map<String, Any?> = buildMap {
        put(KEY_MODE, mode)
        put(KEY_PROFILE, profile)
        put(KEY_FROM_PROFILE, fromProfile)
        put(KEY_WAKE_M, wakeM ?: NO_ANCHOR_M)
        put(KEY_VERDICT, verdict ?: "?")
        val src = source ?: EcoGovernor.WakeSource.GPS
        put(KEY_SOURCE, src)
        // fix-walk-fog-verdict 5.3: wifi/motion-BURST несет окно 180с.
        if (src == EcoGovernor.WakeSource.WIFI || src == EcoGovernor.WakeSource.MOTION) {
            put(KEY_BURST_WINDOW_MS, EcoGovernor.BURST_WINDOW_MS)
        }
    }

    /**
     * Счётчики веток TrustEngine (tracking-reliability): все ключи видимы,
     * включая нули — кандидаты на удаление правил, не «нет данных».
     */
    fun branchPayload(counts: Map<String, Long>): Map<String, Any?> =
        TrustEngine.BRANCH_KEYS.associateWith { counts[it] ?: 0L }
}
