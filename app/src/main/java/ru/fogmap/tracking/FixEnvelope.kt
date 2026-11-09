package ru.fogmap.tracking

import ru.fogmap.data.db.FixInboxEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Чистое ядро приёма фикса (fix-journal 1.1/1.2).
 *
 * Ключ дедупликации и календарный день считаются ОДИН раз — в момент приёма,
 * дальше они не пересчитываются: день точки не зависит от того, когда её
 * разберут. Это же снимает класс «пачка после полуночи уехала в новый день»:
 * день зафиксирован меткой фикса, а не моментом обработки.
 */
data class FixEnvelope(
    val key: String,
    val fixTime: Long,
    val recvMs: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float?,
    val source: String,
    val day: String
) {
    fun toEntity(): FixInboxEntity = FixInboxEntity(
        key = key, fixTime = fixTime, recvMs = recvMs,
        lat = lat, lon = lon, acc = acc, speed = speed,
        source = source, day = day
    )

    companion object {
        const val SOURCE_FUSED = "fused"
        const val SOURCE_MOCK = "mock"

        /** Естественный ключ фикса: метка времени и координаты. */
        fun keyOf(time: Long, lat: Double, lon: Double): String = "$time|$lat|$lon"

        /** Локальная дата метки фикса (yyyy-MM-dd в зоне устройства). */
        fun dayOf(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()

        fun of(
            time: Long,
            lat: Double,
            lon: Double,
            acc: Float,
            speed: Float?,
            isMock: Boolean,
            recvMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault()
        ): FixEnvelope = FixEnvelope(
            key = keyOf(time, lat, lon),
            fixTime = time,
            recvMs = recvMs,
            lat = lat,
            lon = lon,
            acc = acc,
            speed = speed,
            source = if (isMock) SOURCE_MOCK else SOURCE_FUSED,
            day = dayOf(time, zone)
        )
    }
}
