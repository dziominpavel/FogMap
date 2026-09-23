package ru.fogmap.tracking

/**
 * Фильтры точек (spec tracking, задача 3.2 + fix-walk-fog-verdict 3.1):
 * - accuracy хуже порога kind → отброс для тумана/трека (дрейф не открывает);
 * - скорость выше 150 км/ч → отброс;
 * - mock-точки → игнорируются.
 *
 * Пороги kind: STILL/WALK 25, BIKE 40, VEHICLE 100 м.
 */
object LocationFilter {
    /** Порог STILL/WALK (и дефолт без kind) — контракт с speed-гейтам TrustEngine. */
    const val MAX_ACCURACY_M = 25f
    const val BIKE_ACCURACY_M = 40f
    const val VEHICLE_ACCURACY_M = 100f
    const val MAX_SPEED_MS = 41.6667f // 150 км/ч

    data class Input(
        val accuracy: Float?,
        val speed: Float?,
        val isMock: Boolean
    )

    /** Причина решения (tracking-reliability 3.1): каждый отброс объяснен. */
    enum class Reason(val key: String) {
        OK("ok"),
        MOCK("mock"),
        NO_ACCURACY("accuracy"),
        BAD_ACCURACY("accuracy"),
        BAD_SPEED("speed")
    }

    /** Максимальный accuracy для тумана/трека по типу движения. */
    fun maxAccuracyFor(kind: MotionKind): Float = when (kind) {
        MotionKind.STILL, MotionKind.WALK -> MAX_ACCURACY_M
        MotionKind.BIKE -> BIKE_ACCURACY_M
        MotionKind.VEHICLE -> VEHICLE_ACCURACY_M
    }

    /**
     * Причина отброса. [kind] — классификация точки (null → классификация
     * только по speed без гистерезиса; сервис передаёт kind с учётом prev).
     */
    fun reason(i: Input, kind: MotionKind? = null): Reason {
        if (i.isMock) return Reason.MOCK
        val acc = i.accuracy ?: return Reason.NO_ACCURACY
        val k = kind ?: MotionKindClassifier.classify(i.speed, null)
        if (acc > maxAccuracyFor(k) || acc <= 0) return Reason.BAD_ACCURACY
        val s = i.speed
        if (s != null && s > MAX_SPEED_MS) return Reason.BAD_SPEED
        return Reason.OK
    }

    fun accept(i: Input, kind: MotionKind? = null): Boolean =
        reason(i, kind) == Reason.OK
}
