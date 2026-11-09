package ru.fogmap.tracking

/**
 * Передача перехода активности из ресивера в сервис (motion-state 2.1).
 *
 * Ресивер и сервис живут в одном процессе: broadcast стартует процесс, если
 * он был выгружен, поэтому статическая метка видна сервису, который поднимется
 * следом. Метка одноразовая и протухает, чтобы не поднять старую активность.
 */
object ActivitySignal {

    private const val MAX_AGE_MS = 120_000L

    @Volatile
    private var kind: MotionActivity? = null

    @Volatile
    private var atMs: Long = 0L

    fun set(activity: MotionActivity) {
        kind = activity
        atMs = System.currentTimeMillis()
    }

    /** Забирает метку один раз; протухшую возвращает как null. */
    fun take(): MotionActivity? {
        val k = kind ?: return null
        kind = null
        return if (System.currentTimeMillis() - atMs <= MAX_AGE_MS) k else null
    }
}
