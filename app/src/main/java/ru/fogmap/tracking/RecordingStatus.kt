package ru.fogmap.tracking

/**
 * Состояние записи для владельца (change recording-mode, D10).
 *
 * Один объект на плашку карты, строку в Настройках и уведомление: разные
 * представления одного состояния не могут разойтись. Пока запись включена,
 * но фиксов нет — это видно через две минуты, а не после поездки.
 *
 * Чистый объект: время и флаги приходят аргументами.
 */
object RecordingStatus {

    enum class State { OFF, LIVE, STALE }

    data class Snapshot(val state: State, val ageMs: Long)

    /**
     * @param sinceMs момент отсчёта: последний фикс, а если его не было — старт
     * службы ([TrackingHealth.sinceMs]). Иначе при мёртвой с самого старта
     * доставке плашка навсегда показывала бы «Пишем · 0 с назад».
     */
    fun of(recording: Boolean, sinceMs: Long, nowMs: Long): Snapshot {
        val age = if (sinceMs <= 0L) 0L else (nowMs - sinceMs).coerceAtLeast(0L)
        val state = when {
            !recording -> State.OFF
            age >= FixSilence.THRESHOLD_MS -> State.STALE
            else -> State.LIVE
        }
        return Snapshot(state, age)
    }

    /** Подпись для плашки и строки статуса. */
    fun label(s: Snapshot): String = when (s.state) {
        State.OFF -> "Запись выключена"
        State.LIVE -> "Пишем · " + shortAge(s.ageMs) + " назад"
        State.STALE -> "Нет фиксов " + shortAge(s.ageMs)
    }

    /** Возраст коротко: «0 с», «45 с», «4 мин», «2 ч». */
    fun shortAge(ageMs: Long): String {
        val sec = (ageMs / 1000L).coerceAtLeast(0L)
        return when {
            sec < 60L -> sec.toString() + " с"
            sec < 3600L -> (sec / 60L).toString() + " мин"
            else -> (sec / 3600L).toString() + " ч"
        }
    }
}
