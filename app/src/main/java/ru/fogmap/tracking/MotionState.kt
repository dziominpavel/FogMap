package ru.fogmap.tracking

/** Состояние движения телефона (motion-state 1.1): один источник для темпа записи. */
enum class MotionState { STILL, MOVING, UNKNOWN }

/** Виды активности, которые влияют на состояние. */
enum class MotionActivity { STILL, WALK, BIKE, VEHICLE, UNKNOWN }

/** План записи, выведенный из состояния (motion-state 1.2). */
data class MotionPlan(val profile: EcoGovernor.Profile, val holdWake: Boolean)

/**
 * Машина состояний движения (motion-state 1.1): чистая, без Android и БД.
 *
 * Заменяет собой раскиданные по сервису решения «спать или писать»: состояние
 * двигается фиксами, переходами активности и тишиной, а темп записи выводится
 * из него одной функцией [planFor].
 *
 * Fast-path (motion-state 3.1): одиночный фикс с правдоподобной скоростью и
 * хорошей точностью переводит в MOVING сразу, без раскачки серией. Именно
 * серия съедала 2–3 минуты старта поездки после тишины.
 */
class MotionMachine(
    private val movingHoldMs: Long = EcoGovernor.ACTIVE_SPEED_HOLD_MS,
    private val stillAfterMs: Long = STILL_AFTER_MS,
    private val fastAccM: Float = 25f,
    /** Тот же порог, что speed-latch TrustEngine (SPEED_MIN_MPS = 1.0 м/с). */
    private val fastSpeedMps: Float = 1.0f
) {
    var state: MotionState = MotionState.UNKNOWN
        private set
    var lastMovingAtMs: Long = 0L
        private set

    /** Фикс пришёл после паузы больше двух интервалов опроса — замер старта. */
    var lastFixWasFastPath: Boolean = false
        private set

    fun onFix(
        nowMs: Long,
        speedMps: Float?,
        accM: Float?,
        gapMs: Long,
        intervalMs: Long
    ): MotionState {
        lastFixWasFastPath = intervalMs > 0 && gapMs > 2 * intervalMs
        val plausible = speedMps != null && accM != null && accM <= fastAccM && speedMps >= fastSpeedMps
        if (plausible) {
            lastMovingAtMs = nowMs
            state = MotionState.MOVING
            return state
        }
        if (state == MotionState.MOVING && nowMs - lastMovingAtMs > movingHoldMs) {
            state = MotionState.STILL
        }
        return state
    }

    fun onActivity(activity: MotionActivity, nowMs: Long): MotionState {
        when (activity) {
            MotionActivity.WALK, MotionActivity.BIKE, MotionActivity.VEHICLE -> {
                lastMovingAtMs = nowMs
                state = MotionState.MOVING
            }
            MotionActivity.STILL -> {
                if (nowMs - lastMovingAtMs > stillAfterMs) state = MotionState.STILL
            }
            MotionActivity.UNKNOWN -> Unit
        }
        return state
    }

    /** Тишина: удержание движения истекло — считаем, что стоим. */
    fun tick(nowMs: Long): MotionState {
        if (state == MotionState.MOVING && nowMs - lastMovingAtMs > movingHoldMs) {
            state = MotionState.STILL
        }
        return state
    }

    companion object {
        /** Покой подтверждается после двух минут без движения. */
        const val STILL_AFTER_MS = 120_000L

        /**
         * План записи из состояния (motion-state 1.2): MOVING — плотный опрос
         * и wake-lock, STILL — редкий и без lock, UNKNOWN — как было.
         */
        fun planFor(state: MotionState, current: EcoGovernor.Profile): MotionPlan = when (state) {
            MotionState.MOVING -> MotionPlan(EcoGovernor.Profile.ACTIVE, holdWake = true)
            MotionState.STILL -> MotionPlan(EcoGovernor.Profile.STANDBY, holdWake = false)
            MotionState.UNKNOWN -> MotionPlan(current, TrackingWake.neededFor(current, false))
        }
    }
}
