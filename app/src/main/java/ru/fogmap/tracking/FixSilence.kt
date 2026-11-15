package ru.fogmap.tracking

/**
 * Здоровье записи по возрасту последнего фикса (change recording-mode, D7).
 *
 * Раньше «неисправность» определялась по факту запуска сервиса: watchdog видел
 * живой сервис и отчитывался «started», хотя фиксы не приходили часами.
 * Теперь единственный признак — время с последнего принятого фикса.
 *
 * Чистый объект: на вход снимок, на выход действие. Проверяется unit-тестами
 * без Android.
 */
object FixSilence {

    /** Тишина дольше — запись считается неисправной. */
    const val THRESHOLD_MS = 120_000L
    /** Сколько переподписок подряд допустимо, прежде чем перезапускать службу. */
    const val MAX_RESUBSCRIBE = 3
    /** Пауза между перезапусками, чтобы не крутить цикл. */
    const val RESTART_COOLDOWN_MS = 5 * 60_000L

    enum class Action { NONE, RESUBSCRIBE, RESTART, GIVE_UP }

    data class Decision(val action: Action, val ageMs: Long, val failures: Int)

    /**
     * @param nowMs текущее время
     * @param sinceMs момент последнего фикса либо старта службы, если фиксов не было
     * @param recording включён ли тумблер записи
     * @param failures сколько переподписок подряд не дали фикса
     * @param lastRestartMs когда служба последний раз перезапускалась сама
     */
    fun decide(
        nowMs: Long,
        sinceMs: Long,
        recording: Boolean,
        failures: Int,
        lastRestartMs: Long
    ): Decision {
        val age = (nowMs - sinceMs).coerceAtLeast(0L)
        if (!recording || sinceMs <= 0L || age < THRESHOLD_MS) {
            return Decision(Action.NONE, age, failures)
        }
        if (failures < MAX_RESUBSCRIBE) {
            return Decision(Action.RESUBSCRIBE, age, failures + 1)
        }
        val cooldownLeft = RESTART_COOLDOWN_MS - (nowMs - lastRestartMs)
        return if (lastRestartMs > 0L && cooldownLeft > 0L) {
            Decision(Action.GIVE_UP, age, failures)
        } else {
            Decision(Action.RESTART, age, 0)
        }
    }
}
