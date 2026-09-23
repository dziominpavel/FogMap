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

    /** STANDBY: редкий HIGH 60 сек / 0 м (диапазон спеки 30–60, без дистанционного фильтра).
     * BALANCED_POWER для опроса SHALL NOT использоваться (fog-eco-reliability):
     * грубые фиксы 36–349 м не подтверждают движение и не считают смещение. */
    const val STANDBY_INTERVAL_MS = 60_000L
    const val STANDBY_MIN_MS = 30_000L
    const val STANDBY_DIST_M = 0f

    /** BURST: HIGH 6 сек для решения, окно до 180 сек (fix-walk-fog-verdict 5.1). */
    const val BURST_INTERVAL_MS = 6_000L
    const val BURST_MIN_MS = 5_000L
    const val BURST_DIST_M = 0f
    const val BURST_WINDOW_MS = 180_000L

    /** Подтверждение статики: окно TrustEngine (5 точек в 25 м). */
    const val STAND_CONFIRM_STREAK = 5

    /** Пробуждение по смещению от якоря STANDBY (спека 100–150 м). */
    const val WAKE_DISTANCE_M = 100.0

    /** Дебаунс ACTIVE->STANDBY против дребезга (BURST-переходы без дебаунса). */
    const val STANDBY_DEBOUNCE_MS = 30_000L

    /**
     * Speed-latch (fix-walk-fog-verdict): единый порог [TrustEngine.SPEED_MIN_MPS]
     * = 1.0 м/с (ходьба/велосипед/машина). Accuracy не хуже 25 м.
     */
    const val SPEED_LATCH_MS = TrustEngine.SPEED_MIN_MPS
    const val SPEED_LATCH_MAX_ACC_M = TrustEngine.SPEED_GATE_MAX_ACC_M

    /**
     * Удержание ACTIVE после скорости (fog-eco-reliability, диапазон спеки 3–5 мин):
     * светофор/пробка не роняют профиль mid-trip. Точное значение — по полевому
     * замеру (см. design.md Open Questions).
     */
    const val ACTIVE_SPEED_HOLD_MS = 180_000L

    /**
     * Источник пробуждения для eco_state (wake-balance-parking 4.1):
     * строковый контракт лога, координат нет.
     */
    object WakeSource {
        const val GPS = "gps"
        const val MOTION = "motion"
        const val WIFI = "wifi"
        const val TIMEOUT = "timeout"
        const val RESTART = "restart"
    }

    fun paramsFor(p: Profile): FusedParams = when (p) {
        Profile.ACTIVE -> FusedParams(ACTIVE_INTERVAL_MS, ACTIVE_MIN_MS, ACTIVE_DIST_M, Priority.PRIORITY_HIGH_ACCURACY)
        Profile.STANDBY -> FusedParams(STANDBY_INTERVAL_MS, STANDBY_MIN_MS, STANDBY_DIST_M, Priority.PRIORITY_HIGH_ACCURACY)
        Profile.BURST -> FusedParams(BURST_INTERVAL_MS, BURST_MIN_MS, BURST_DIST_M, Priority.PRIORITY_HIGH_ACCURACY)
    }

    /** Смещение от якоря достаточное для BURST (чистое, тестируется). */
    fun isWakeSignal(anchorLat: Double, anchorLon: Double, lat: Double, lon: Double): Boolean =
        FogRepository.haversineM(anchorLat, anchorLon, lat, lon) >= WAKE_DISTANCE_M

    /** То же по готовой дистанции в метрах (чистое, тестируется). */
    fun isWakeDistance(distM: Long): Boolean = distM.toDouble() >= WAKE_DISTANCE_M

    /**
     * Speed-latch (fix-walk-fog-verdict, чистое): единый порог ≥ 1.0 м/с,
     * accuracy не хуже 25 м — ходьба/велосипед/машина из BURST в ACTIVE.
     */
    fun isSpeedLatch(speedMps: Float?, accM: Float?): Boolean =
        speedMps != null && accM != null &&
            speedMps.toDouble() >= SPEED_LATCH_MS && accM <= SPEED_LATCH_MAX_ACC_M

    /**
     * Решение STANDBY (fog-eco-reliability, чистое, тестируется): GPS-смещение
     * будит всегда, motion не требуется — входа с motion в сигнатуре нет
     * намеренно. MOVING-вердикт будит даже без смещения (ранний старт).
     */
    fun standbyTarget(state: TrustEngine.State, distM: Long): Profile? =
        if (state == TrustEngine.State.MOVING || isWakeDistance(distM)) Profile.BURST else null

    /**
     * Решение BURST (fog-eco-reliability, чистое, тестируется): в ACTIVE —
     * по MOVING-вердикту ИЛИ по speed-latch. Таймаут окна остается в сервисе.
     */
    fun burstTarget(state: TrustEngine.State, speedMps: Float?, accM: Float?): Profile? =
        if (state == TrustEngine.State.MOVING || isSpeedLatch(speedMps, accM)) Profile.ACTIVE else null

    /**
     * Готовность ACTIVE уйти в STANDBY (fix-walk-fog-verdict 5.2): STAND-серия
     * + дебаунс + удержание после скорости + guard — при MOVING-вердикте сон
     * запрещено (двойная страховка; в сервисе streak и так сбрасывается).
     * lastSpeedLatchMs <= 0 — скорости еще не было, удержание не применяется.
     */
    fun activeMayStandby(
        standStreak: Int,
        nowMs: Long,
        lastStandbyEnterMs: Long,
        lastSpeedLatchMs: Long,
        state: TrustEngine.State = TrustEngine.State.STAND
    ): Boolean =
        state != TrustEngine.State.MOVING &&
            standStreak >= STAND_CONFIRM_STREAK &&
            nowMs - lastStandbyEnterMs >= STANDBY_DEBOUNCE_MS &&
            (lastSpeedLatchMs <= 0L || nowMs - lastSpeedLatchMs >= ACTIVE_SPEED_HOLD_MS)

    /** Строковое имя профиля для DataStore/логов (контракт с EcoProfiles). */
    fun nameOf(p: Profile): String = p.name

    fun fromName(v: String?): Profile = when (v) {
        "STANDBY" -> Profile.STANDBY
        "BURST" -> Profile.BURST
        else -> Profile.ACTIVE
    }
}
