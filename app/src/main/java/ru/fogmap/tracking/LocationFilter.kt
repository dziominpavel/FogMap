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

    fun accept(i: Input): Boolean {
        if (i.isMock) return false
        val acc = i.accuracy ?: return false
        if (acc > MAX_ACCURACY_M || acc <= 0) return false
        val s = i.speed
        if (s != null && s > MAX_SPEED_MS) return false
        return true
    }
}
