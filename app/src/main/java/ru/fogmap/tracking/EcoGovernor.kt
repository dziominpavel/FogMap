package ru.fogmap.tracking

import com.google.android.gms.location.Priority
import ru.fogmap.data.FogRepository

/**
 * Эко-губернатор опроса (battery-eco 2.1, чистый объект без Android-состояния).
 * Профили Fused и переходы ACTIVE/STANDBY/BURST. Сервис только хранит
 * текущее состояние и дергает переподписку; математика доверия не меняется.
 */
object EcoGovernor {
    enum class Profile { ACTIVE, STANDBY, BURST }

    data class FusedParams(
        val intervalMs: Long,
        val minIntervalMs: Long,
        val distanceM: Float,
        val priority: Int
    )

    /** ACTIVE: текущий плотный HIGH 8 сек / 15 м (без изменений). */
    const val ACTIVE_INTERVAL_MS = 8_000L
    const val ACTIVE_MIN_MS = 5_000L
    const val ACTIVE_DIST_M = 15f

    /** STANDBY: редкий BALANCED 90 сек / 150 м (диапазон спеки 60–180 / 100–200). */
    const val STANDBY_INTERVAL_MS = 90_000L
    const val STANDBY_MIN_MS = 60_000L
    const val STANDBY_DIST_M = 150f

    /** BURST: короткий HIGH 6 сек для решения, окно 60–90 сек. */
    const val BURST_INTERVAL_MS = 6_000L
    const val BURST_MIN_MS = 5_000L
    const val BURST_DIST_M = 0f
    const val BURST_WINDOW_MS = 75_000L

    /** Подтверждение статики: окно TrustEngine (5 точек в 25 м). */
    const val STAND_CONFIRM_STREAK = 5

    /** Пробуждение по смещению от якоря STANDBY (спека 100–150 м). */
    const val WAKE_DISTANCE_M = 100.0

    /** Дебаунс ACTIVE->STANDBY против дребезга (BURST-переходы без дебаунса). */
    const val STANDBY_DEBOUNCE_MS = 30_000L

    fun paramsFor(p: Profile): FusedParams = when (p) {
        Profile.ACTIVE -> FusedParams(ACTIVE_INTERVAL_MS, ACTIVE_MIN_MS, ACTIVE_DIST_M, Priority.PRIORITY_HIGH_ACCURACY)
        Profile.STANDBY -> FusedParams(STANDBY_INTERVAL_MS, STANDBY_MIN_MS, STANDBY_DIST_M, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        Profile.BURST -> FusedParams(BURST_INTERVAL_MS, BURST_MIN_MS, BURST_DIST_M, Priority.PRIORITY_HIGH_ACCURACY)
    }

    /** Смещение от якоря достаточное для BURST (чистое, тестируется). */
    fun isWakeSignal(anchorLat: Double, anchorLon: Double, lat: Double, lon: Double): Boolean =
        FogRepository.haversineM(anchorLat, anchorLon, lat, lon) >= WAKE_DISTANCE_M

    /** Строковое имя профиля для DataStore/логов (контракт с EcoProfiles). */
    fun nameOf(p: Profile): String = p.name

    fun fromName(v: String?): Profile = when (v) {
        "STANDBY" -> Profile.STANDBY
        "BURST" -> Profile.BURST
        else -> Profile.ACTIVE
    }
}
