package ru.fogmap.tracking

/**
 * Классификация движения (fix-walk-fog-verdict, spec gps-trust):
 * speed+acc полосы с гистерезисом ±0.3, без Activity Recognition API.
 *
 * Полосы: STILL <1.0, WALK 1.0–2.5, BIKE 2.5–8.0, VEHICLE ≥8.0 (м/с).
 * Гистерезис от границы текущей полосы, чтобы kind не мигал на GPS-шуме ±0.2.
 */
enum class MotionKind { STILL, WALK, BIKE, VEHICLE }

object MotionKindClassifier {
    const val WALK_MIN_MS = 1.0f
    const val BIKE_MIN_MS = 2.5f
    const val VEHICLE_MIN_MS = 8.0f
    const val HYSTERESIS_MS = 0.3f

    /** Нижняя/верхняя граница полосы kind (для гистерезиса). */
    private fun lowOf(k: MotionKind): Float = when (k) {
        MotionKind.STILL -> 0f
        MotionKind.WALK -> WALK_MIN_MS
        MotionKind.BIKE -> BIKE_MIN_MS
        MotionKind.VEHICLE -> VEHICLE_MIN_MS
    }

    private fun highOf(k: MotionKind): Float = when (k) {
        MotionKind.STILL -> WALK_MIN_MS
        MotionKind.WALK -> BIKE_MIN_MS
        MotionKind.BIKE -> VEHICLE_MIN_MS
        MotionKind.VEHICLE -> Float.MAX_VALUE
    }

    private fun band(v: Float): MotionKind = when {
        v < WALK_MIN_MS -> MotionKind.STILL
        v < BIKE_MIN_MS -> MotionKind.WALK
        v < VEHICLE_MIN_MS -> MotionKind.BIKE
        else -> MotionKind.VEHICLE
    }

    /**
     * Чистая классификация (без AR API): [speed] Fused и предыдущий [prev]
     * для гистерезиса. Нет speed — сохраняем prev (или STILL на холодном старте).
     */
    fun classify(speed: Float?, prev: MotionKind?): MotionKind {
        if (speed == null || speed < 0f) return prev ?: MotionKind.STILL
        val target = band(speed)
        if (prev == null || prev == target) return target
        val up = target.ordinal > prev.ordinal
        return if (up) {
            // Уход вверх только за HYSTERESIS за верхнюю границу prev.
            if (speed >= highOf(prev) + HYSTERESIS_MS) target else prev
        } else {
            // Уход вниз только на HYSTERESIS ниже нижней границы prev.
            if (speed < lowOf(prev) - HYSTERESIS_MS) target else prev
        }
    }
}
