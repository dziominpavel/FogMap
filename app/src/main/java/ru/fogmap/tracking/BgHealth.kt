package ru.fogmap.tracking

/**
 * Проверка фоновой записи (fix-track-gap-visibility 5.1–5.4): чистая
 * классификация состояния. Экран только измеряет и показывает — разрешений не
 * просит и настройки не меняет.
 *
 * Возраст последней точки — из БД, возраст heartbeat — из канала `LIFE`.
 * Пункт «процесс остановлен системой» появляется только когда системный
 * журнал exit-info это подтверждает (API 30+).
 */
object BgHealth {

    /** Точка старше 10 минут = запись сейчас не идет (flush-интервал 15 с). */
    const val STALE_POINT_MS = 10 * 60_000L

    /** Heartbeat старше 15 минут = канал жизни молчит (период 5 минут). */
    const val STALE_HEARTBEAT_MS = 15 * 60_000L

    enum class Problem {
        /** Нет свежих точек: запись не идет. */
        NO_RECENT_POINTS,

        /** Уведомления выключены: FGS-уведомление не видно в шторке. */
        NOTIFICATIONS,

        /** Приложение под оптимизацией батареи: система может убить запись. */
        BATTERY,

        /** Пакет уже останавливала система — без автозапуска это повторится. */
        KILLED
    }

    /** Причина последней смерти процесса из системного журнала. */
    data class Exit(
        val reason: String,
        val ageMs: Long,
        val vendor: String?,
        val systemStop: Boolean
    )

    data class Snapshot(
        val paused: Boolean,
        val lastPointAgeMs: Long?,
        val lastHeartbeatAgeMs: Long?,
        val notificationsEnabled: Boolean,
        val batteryRestricted: Boolean,
        val lastExit: Exit?
    )

    fun ageMs(nowMs: Long, thenMs: Long?): Long? =
        thenMs?.let { (nowMs - it).coerceAtLeast(0L) }

    fun isStalePoint(ageMs: Long?): Boolean = ageMs == null || ageMs > STALE_POINT_MS

    fun isStaleHeartbeat(ageMs: Long?): Boolean = ageMs == null || ageMs > STALE_HEARTBEAT_MS

    /** Идет ли запись прямо сейчас: не пауза и есть свежая точка. */
    fun isRecording(s: Snapshot): Boolean = !s.paused && !isStalePoint(s.lastPointAgeMs)

    fun problems(s: Snapshot): List<Problem> = buildList {
        if (s.notificationsEnabled.not()) add(Problem.NOTIFICATIONS)
        if (s.batteryRestricted) add(Problem.BATTERY)
        if (s.lastExit?.systemStop == true) add(Problem.KILLED)
        if (!s.paused && isStalePoint(s.lastPointAgeMs)) add(Problem.NO_RECENT_POINTS)
    }

    /**
     * Остановка не по нашей воле: OEM-тег в описании, нехватка памяти, ANR,
     * системный freezer или принудительная остановка пользователем. Для всех
     * этих причин автовосстановление изнутри пакета невозможно.
     */
    fun isSystemStop(reason: String, vendor: String?): Boolean {
        if (vendor != null) return true
        return reason in setOf(
            "low_memory", "anr", "crash", "crash_native", "signaled", "freezer",
            "excessive_resources", "package_state_change", "package_updated",
            "package_disabled", "user_requested"
        )
    }

    /** Разбор payload `LIFE/exit_reason` в модель экрана. */
    fun exitFrom(payload: Map<String, Any?>): Exit? {
        val reason = payload[TrackingLife.KEY_REASON] as? String ?: return null
        val age = (payload[TrackingLife.KEY_AGE_MS] as? Number)?.toLong() ?: 0L
        val vendor = payload[TrackingLife.KEY_VENDOR] as? String
        return Exit(reason, age, vendor, isSystemStop(reason, vendor))
    }
}
