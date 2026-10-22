package ru.fogmap.tracking

/**
 * Условие интервала тишины Fused (fix-track-gap-visibility 2.1). Чистый
 * объект: в решении НЕТ содержимого буферов — только фактический интервал с
 * последнего подтвержденного тика записи. Иначе пачка фиксов после разморозки
 * маскирует дыру (21.10: 226 минут при одной записи no_fix).
 */
object SilenceGap {
    /** Порог тишины: 5 минут без доставки геолокации. */
    const val THRESHOLD_MS = 5 * 60_000L

    fun shouldReport(gapMs: Long, paused: Boolean, canTrack: Boolean): Boolean =
        gapMs >= THRESHOLD_MS && !paused && canTrack
}
