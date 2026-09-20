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
        ecoGpsMs: Long
    ): Map<String, Any?> = mapOf(
        KEY_BATCH to batch,
        KEY_REJECTED to rejected,
        KEY_TXN_MS to txnMs,
        KEY_NEW_CELLS to newCells,
        KEY_MODE to mode,
        KEY_PROFILE to profile,
        KEY_ECO_FIX to ecoFix,
        KEY_ECO_STAND to ecoStand,
        KEY_ECO_GPS_MS to ecoGpsMs
    )

    fun ecoStatePayload(
        mode: String,
        profile: String,
        fromProfile: String,
        wakeM: Long?,
        verdict: String?
    ): Map<String, Any?> = mapOf(
        KEY_MODE to mode,
        KEY_PROFILE to profile,
        KEY_FROM_PROFILE to fromProfile,
        KEY_WAKE_M to (wakeM ?: NO_ANCHOR_M),
        KEY_VERDICT to (verdict ?: "?")
    )
}
