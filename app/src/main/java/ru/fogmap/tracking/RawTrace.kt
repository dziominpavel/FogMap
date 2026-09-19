package ru.fogmap.tracking

import ru.fogmap.data.FogRepository
import ru.fogmap.data.db.RawFixEntity

/**
 * Чистый маппер черного ящика (track-debug 1.3): из fix + вердикта в строку
 * raw_fixes. Без Android-зависимостей — покрывается JVM-тестами.
 */
object RawTrace {
    fun build(
        time: Long,
        lat: Double,
        lon: Double,
        acc: Float,
        speed: Float?,
        isMock: Boolean,
        filter: String,
        state: String?,
        trust: Int?,
        openFog: Int?,
        rejectReason: String?,
        history: List<TrustEngine.HistPoint>,
        prev: TrustEngine.PrevState? = null
    ): RawFixEntity {
        var implied: Double? = null
        var cap: Double? = null
        var teleport: Int? = null
        val last = history.lastOrNull()
        if (last != null) {
            val dtS = ((time - last.time) / 1000).coerceAtLeast(1)
            val shiftM = FogRepository.haversineM(last.lat, last.lon, lat, lon)
            implied = shiftM / dtS
            teleport = if (shiftM > TrustEngine.TELEPORT_M) 1 else 0
            val median = TrustEngine.medianImplied(history)
            // Зеркало потолка TrustEngine (track-fix 19.09): пол + медиана +
            // рывок + старт из медленного.
            val rec = TrustEngine.maxRecentImplied(history)
            val slowStart =
                if (median < TrustEngine.SLOW_MED_MS) TrustEngine.START_CAP_MS else 0.0
            cap = when {
                prev != null ->
                    maxOf(
                        TrustEngine.MOVING_CAP_MIN_MS,
                        TrustEngine.MOVING_CAP_FACTOR * median,
                        TrustEngine.SPURT_FACTOR * rec, slowStart
                    )
                history.size >= 3 -> TrustEngine.STAND_CAP_MS
                else -> TrustEngine.MOVING_CAP_MIN_MS
            }
        }
        val day = runCatching {
            java.time.Instant.ofEpochMilli(time)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrDefault(java.time.LocalDate.now().toString())
        return RawFixEntity(
            day = day, time = time, lat = lat, lon = lon,
            acc = acc, speed = speed, isMock = isMock, filter = filter,
            state = state, trust = trust, openFog = openFog, rejectReason = rejectReason,
            implied = implied, cap = cap, teleport = teleport
        )
    }
}
