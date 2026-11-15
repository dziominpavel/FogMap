package ru.fogmap.tracking

/**
 * Снимок здоровья записи для внешних проверок (change recording-mode, D7).
 *
 * Сервис живёт в том же процессе, но watchdog работает вне его объекта, а
 * возраст последнего фикса — единственный признак, по которому отличается
 * «пишем» от «служба запущена и молчит». Без общего снимка watchdog снова
 * видел бы только факт запуска.
 */
object TrackingHealth {

    /** Момент приёма последнего фикса (0 — фиксов ещё не было). */
    @Volatile
    var lastFixWallMs: Long = 0L
        private set

    /** Момент старта текущей службы. */
    @Volatile
    var serviceStartWallMs: Long = 0L
        private set

    @Volatile
    var serviceAlive: Boolean = false
        private set

    /**
     * Момент последнего самоперезапуска службы. Живёт здесь, а не в службе:
     * перезапуск уничтожает экземпляр, и кулдаун в поле экземпляра обнулялся —
     * цикл «три переподписки → рестарт» шёл бы бесконечно.
     */
    @Volatile
    var lastSelfRestartWallMs: Long = 0L

    /** База отсчёта: фикс, а если фиксов не было — старт службы. */
    fun sinceMs(): Long = if (lastFixWallMs > 0L) lastFixWallMs else serviceStartWallMs

    fun ageMs(nowMs: Long = System.currentTimeMillis()): Long {
        val since = sinceMs()
        return if (since <= 0L) 0L else (nowMs - since).coerceAtLeast(0L)
    }

    fun onFix(nowMs: Long) {
        lastFixWallMs = nowMs
    }

    fun onServiceStart(nowMs: Long) {
        serviceStartWallMs = nowMs
        serviceAlive = true
    }

    fun onServiceStop(nowMs: Long = System.currentTimeMillis()) {
        serviceAlive = false
        // Возраст считаем от остановки: иначе мёртвая служба навсегда
        // «старела» бы от последнего фикса и врала бы в диагностике.
        serviceStartWallMs = nowMs
        lastFixWallMs = 0L
    }
}
