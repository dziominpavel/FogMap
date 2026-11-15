package ru.fogmap.tracking

import com.google.android.gms.location.Priority

/**
 * Параметры подписки на геолокацию (change recording-mode, D1).
 *
 * Один профиль на всю запись: интервал 5 с, минимальный 3 с, без
 * дистанционного фильтра. Ступеней STANDBY/BURST больше нет — спящий опрос
 * не замечает начало поездки, а признак «стою» берётся из истории, которой
 * при оборванной доставке просто нет.
 *
 * Чистый объект: значения проверяются unit-тестом без Android.
 */
object RecordingParams {

    /** Основной интервал подписки, мс. */
    const val INTERVAL_MS = 5_000L
    /** Минимальный интервал доставки (провайдер может прислать чаще), мс. */
    const val MIN_INTERVAL_MS = 3_000L
    /** Дистанционный фильтр отключён: точка нужна и без смещения. */
    const val MIN_DISTANCE_M = 0f

    data class FusedParams(
        val intervalMs: Long,
        val minIntervalMs: Long,
        val distanceM: Float,
        val priority: Int
    )

    fun fused(): FusedParams = FusedParams(
        intervalMs = INTERVAL_MS,
        minIntervalMs = MIN_INTERVAL_MS,
        distanceM = MIN_DISTANCE_M,
        priority = Priority.PRIORITY_HIGH_ACCURACY
    )
}
