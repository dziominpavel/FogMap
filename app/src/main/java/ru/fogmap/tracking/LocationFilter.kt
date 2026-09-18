package ru.fogmap.tracking

/**
 * Фильтры точек (spec tracking, задача 3.2):
 * - accuracy хуже 25 м → отброс (дрейф не открывает туман);
 * - скорость выше 150 км/ч → отброс;
 * - mock-точки → игнорируются.
 */
object LocationFilter {
    const val MAX_ACCURACY_M = 25f
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

    fun reason(i: Input): Reason {
        if (i.isMock) return Reason.MOCK
        val acc = i.accuracy ?: return Reason.NO_ACCURACY
        if (acc > MAX_ACCURACY_M || acc <= 0) return Reason.BAD_ACCURACY
        val s = i.speed
        if (s != null && s > MAX_SPEED_MS) return Reason.BAD_SPEED
        return Reason.OK
    }

    fun accept(i: Input): Boolean = reason(i) == Reason.OK
}
