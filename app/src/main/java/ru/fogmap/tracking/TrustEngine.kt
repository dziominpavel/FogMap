package ru.fogmap.tracking

import ru.fogmap.data.FogRepository
import ru.fogmap.fog.FogGrid
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Доверие GPS по последовательности точек (gps-trust-filter, spec gps-trust).
 *
 * Идея владельца: одна точка врет легко, пять точек с постоянной скоростью —
 * почти нет. Поэтому верим не точке, а серии: доверие падает мгновенно
 * (один прыжок), растет медленно (серия согласных точек).
 *
 * Чистый объект без состояния и Android-зависимостей: состояние приходит
 * аргументом ([PrevState]) и возвращается в [Verdict.next] — сервис только
 * хранит его между вызовами. Полностью покрыт unit-тестами.
 *
 * Ворота разделены: точка пишется в трек почти всегда, а туман открывает
 * только [Verdict.openFog]. Прыжок дополнительно считается в `jump`
 * ([Verdict.countReject]), заморозка статики — нет (это не отброс,
 * просто открывать нечего).
 */
object TrustEngine {
    // --- Пороги (калибруются полевым тестом, см. fog-pyramid) ---
    const val HISTORY_MAX = 8
    const val STATIC_MIN_POINTS = 5
    const val STATIC_RADIUS_M = 25.0
    /** Из статики быстрее brisk walk — подозрение (машина так не трогается за 8 сек). */
    const val STAND_CAP_MS = 3.0
    const val MOVING_CAP_MIN_MS = 15.0
    const val MOVING_CAP_FACTOR = 2.5
    /**
     * Сброс окна после трогания (track-fix 19.09): движение быстрее —
     * существенное (выше GPS-джиттера ~0.5 м/с), стояночные нули
     * выкидываются из окна. Медленное шарканье окно не сбрасывает.
     */
    const val PULL_RESET_MIN_MS = 1.0
    /**
     * Рывок относительно недавней скорости (track-fix 19.09): до 3x от
     * максимума последних сегментов. Держит продолжение разгона, пока
     * медиана догоняет (первая крейсерская точка после старта).
     */
    const val SPURT_FACTOR = 3.0
    /**
     * Старт из медленного контекста (track-fix 19.09, replay 19.09):
     * медиана ниже — история ползучая/стояночная, судить рывок по ней
     * нельзя (44 ложных SUSPECT за день при чистом GPS). Потолок 28 м/с
     * (~225 м за 8 с): запуски до шоссейных скоростей проходят, выбросы
     * 250 м+ ловятся, телепорт-гейт (>500 м) и ворота C страхуют дальше.
     */
    const val SLOW_MED_MS = 5.0
    const val START_CAP_MS = 28.0
    const val HEADING_MIN_SPEED_MS = 8.0
    const val HEADING_MAX_TURN_DEG = 120.0
    const val TRUST_START = 40
    const val TRUST_STEP = 15
    const val TRUST_MAX = 100
    const val TRUST_SUSPECT = 10
    // Пороги открытия/кисти — единый контракт в FogGrid (TRUST_OPEN/TRUST_HIGH),
    // здесь используются через него, чтобы слои не разъехались.
    const val TRUST_STAND = 80
    const val TRUST_RETURN = 70
    /** Accuracy хуже — доверие упирается в потолок (плохой GPS не наберет HIGH). */
    const val ACC_TRUST_CAP_M = 15f
    const val ACC_TRUST_MAX = 60
    /** Возврат ближе половины выброса к якорю = выброс подтвержден. */
    const val RETURN_RATIO = 0.5
    /**
     * Телепорт-гейт (trust-v2 A): смещение между соседними доставками больше —
     * SUSPECT независимо от dt. Честные 8 сек даже на 150 км/ч — 333 м.
     */
    const val TELEPORT_M = 500.0
    /**
     * Сброс после тишины (trust-v2 B): dt больше — контекст аннулирован,
     * доверие в стартовое, первая точка туман не открывает.
     */
    const val SILENCE_RESET_S = 120L
    /**
     * Первая точка после долгой тишины (wake-balance-parking 3.1): честный
     * утренний выезд 100–1500 м с чистым accuracy — кандидат ворот C
     * (откроется подтверждением следующих), а не вечный SUSPECT.
     * Дальние выбросы (4 км — аэропорт) по-прежнему ловит телепорт-гейт.
     */
    const val WAKE_FIRST_MIN_M = 100.0
    const val WAKE_FIRST_MAX_M = 1500.0

    enum class State { STAND, MOVING, SUSPECT }

    data class HistPoint(val time: Long, val lat: Double, val lon: Double, val acc: Float)

    data class PrevState(
        val state: State,
        val trust: Int,
        val anchorLat: Double? = null,
        val anchorLon: Double? = null,
        val suspectLat: Double? = null,
        val suspectLon: Double? = null
    )

    data class Verdict(
        val state: State,
        val trust: Int,
        val openFog: Boolean,
        /** Причина для счетчика отбросов (сейчас только jump) или null. */
        val countReject: String?,
        val next: PrevState,
        /**
         * Сброс истории (track-fix 19.09): трогание после STAND — сервис
         * выкидывает стояночные нули из окна, иначе отравленная медиана
         * держит потолок 15 м/с еще ~8 точек (~1 км серого).
         */
        val resetHistory: Boolean = false
    )

    fun evaluate(prev: PrevState?, history: List<HistPoint>, new: HistPoint): Verdict {
        if (history.isEmpty()) {
            val next = PrevState(State.STAND, TRUST_START)
            return Verdict(State.STAND, TRUST_START, false, null, next)
        }
        val last = history.last()
        val dtS = ((new.time - last.time) / 1000).coerceAtLeast(1)
        val shiftM = FogRepository.haversineM(last.lat, last.lon, new.lat, new.lon)
        val implied = shiftM / dtS

        // 1. Возврат в якорь после подозрения = выброс подтвержден.
        if (prev?.state == State.SUSPECT &&
            prev.anchorLat != null && prev.anchorLon != null &&
            prev.suspectLat != null && prev.suspectLon != null
        ) {
            val out = FogRepository.haversineM(
                prev.anchorLat, prev.anchorLon, prev.suspectLat, prev.suspectLon
            )
            val back = FogRepository.haversineM(prev.anchorLat, prev.anchorLon, new.lat, new.lon)
            if (out > 1.0 && back < RETURN_RATIO * out) {
                val next = PrevState(State.STAND, TRUST_RETURN)
                return Verdict(State.STAND, TRUST_RETURN, false, null, next)
            }
        }

        // 2. Статичный кластер = стоим, открытие заморожено (не отброс!).
        // Меряем смещение от СТАРОГО края истории, а не разброс скользящего
        // окна: окно едет вместе с медленным пешеходом и никогда бы не
        // сработало, а якорь держит (час в офисе = смещение в метрах).
        if (history.size + 1 >= STATIC_MIN_POINTS) {
            val anchor = history.first()
            val drift = FogRepository.haversineM(anchor.lat, anchor.lon, new.lat, new.lon)
            if (drift <= STATIC_RADIUS_M) {
                return afterSilence(
                    Verdict(State.STAND, TRUST_STAND, false, null, PrevState(State.STAND, TRUST_STAND)),
                    dtS
                )
            }
        }

        // 2в. Выход после долгой тишины (wake-balance-parking 3.1): первая
        // движущаяся точка с правдоподобным наземным профилем — кандидат
        // ворот C со стартовым доверием, а не вечное вето по старой медиане.
        // Утро 21.09: 838 м после ночи уходило в SUSPECT/jump, хотя следующие
        // точки подтверждали движение. Дальние выбросы (>1500 м) пропускаем
        // дальше на телепорт-гейт.
        if (dtS > SILENCE_RESET_S &&
            shiftM >= WAKE_FIRST_MIN_M && shiftM <= WAKE_FIRST_MAX_M &&
            new.acc <= LocationFilter.MAX_ACCURACY_M
        ) {
            return Verdict(
                State.MOVING, TRUST_START, true, null,
                PrevState(State.MOVING, TRUST_START),
                resetHistory = true
            )
        }
        // 2г. Продолжение пробуждения (wake-balance-parking 3.1): вторая точка
        // BURST-пачки через доли секунды после первой (грубый STANDBY-фикс
        // против точного GPS — implied артефактно огромен, скорость Fused
        // при этом автомобильная). Судить ее по стояночной медиане нельзя:
        // в пределах телепорта и с чистым accuracy — кандидат ворот C.
        // Проверка «только что проснулись»: предпоследний разрыв — тишина.
        if (history.size >= 2) {
            val a = history[history.size - 2]
            val b = history.last()
            val gapS = ((b.time - a.time) / 1000).coerceAtLeast(1)
            if (gapS > SILENCE_RESET_S &&
                shiftM <= TELEPORT_M &&
                new.acc <= LocationFilter.MAX_ACCURACY_M
            ) {
                return Verdict(
                    State.MOVING, TRUST_START, true, null,
                    PrevState(State.MOVING, TRUST_START),
                    resetHistory = true
                )
            }
        }

        // 2б. Телепорт-гейт (trust-v2 A): абсолютное смещение между соседними
        // доставками. dt здесь не участвует намеренно: именно огромный dt
        // делал implied слепым (кейс «аэропорт через тишину»). Исключение —
        // быстрое движение с правдоподобной скоростью (выезд из тоннеля):
        // серия уже доказала скорость, одиночный разрыв — не телепорт.
        if (shiftM > TELEPORT_M) {
            val movingPlausible = prev?.state == State.MOVING &&
                implied <= maxOf(MOVING_CAP_MIN_MS, MOVING_CAP_FACTOR * medianImplied(history))
            if (!movingPlausible) {
                val next = PrevState(
                    State.SUSPECT, TRUST_SUSPECT,
                    anchorLat = last.lat, anchorLon = last.lon,
                    suspectLat = new.lat, suspectLon = new.lon
                )
                return Verdict(
                    State.SUSPECT, TRUST_SUSPECT, false, FogRepository.REJECT_JUMP, next
                )
            }
        }

        // 3. Прыжок по скорости: потолок — максимум из оценок (track-fix 19.09).
        // Ни одна оценка в одиночку не работает: медиана отравляется ползучим
        // контекстом (44 ложных SUSPECT 19.09 из стояночно-ползучего окна),
        // недавний максимум не видит старт с места. Поэтому:
        // пол (15) + медиана 2.5x (ровный крейсер, проверено 726 точками) +
        // рывок 3x (продолжение разгона) + старт из медленного (28).
        // Короткая история (< 3 точек) — контекст неизвестен: мягкий потолок.
        // Настоящие выбросы ловят телепорт-гейт, разворот и ворота C.
        val med = medianImplied(history)
        val rec = maxRecentImplied(history)
        val slowStart = if (med < SLOW_MED_MS) START_CAP_MS else 0.0
        val cap = when {
            prev != null ->
                maxOf(MOVING_CAP_MIN_MS, MOVING_CAP_FACTOR * med, SPURT_FACTOR * rec, slowStart)
            history.size >= 3 -> STAND_CAP_MS
            else -> MOVING_CAP_MIN_MS
        }
        // 4. Резкий разворот на скорости (разворот на 180 за 8 сек на 50 км/ч
        // физически невозможен; пешие виляния и медленные развороты правилом
        // не ловятся — порог скорости 8 м/с).
        val headingSuspect = prev != null && implied > HEADING_MIN_SPEED_MS &&
            history.size >= 2 && sharpTurn(history[history.size - 2], last, new)
        if (implied > cap || headingSuspect) {
            val next = PrevState(
                State.SUSPECT, TRUST_SUSPECT,
                anchorLat = last.lat, anchorLon = last.lon,
                suspectLat = new.lat, suspectLon = new.lon
            )
            return Verdict(State.SUSPECT, TRUST_SUSPECT, false, FogRepository.REJECT_JUMP, next)
        }

        // 5. Согласная точка: доверие растет медленно, плохой accuracy — в потолок.
        var trust = minOf(TRUST_MAX, (prev?.trust ?: TRUST_START) + TRUST_STEP)
        if (new.acc > ACC_TRUST_CAP_M) trust = minOf(trust, ACC_TRUST_MAX)
        return afterSilence(
            when (prev?.state) {
                State.MOVING -> {
                    val open = trust >= FogGrid.TRUST_OPEN
                    Verdict(State.MOVING, trust, open, null, PrevState(State.MOVING, trust))
                }
                else -> {
                    // Из STAND/SUSPECT: шевеление в пределах шума — еще стоим,
                    // заметное смещение — начало движения (не прыжок: см. шаг 3).
                    // Существенное трогание после STAND помечает сброс окна
                    // (джиттер 0.5 м/с окно не сбрасывает — иначе статика
                    // никогда не наберет 5 точек).
                    if (implied > 0.5) {
                        val open = trust >= FogGrid.TRUST_OPEN
                        Verdict(
                            State.MOVING, trust, open, null, PrevState(State.MOVING, trust),
                            resetHistory = prev?.state == State.STAND && implied > PULL_RESET_MIN_MS
                        )
                    } else {
                        val standTrust = minOf(TRUST_STAND, (prev?.trust ?: TRUST_START) + TRUST_STEP)
                        Verdict(State.STAND, standTrust, false, null, PrevState(State.STAND, standTrust))
                    }
                }
            },
            dtS
        )
    }

    /**
     * Сброс после тишины (trust-v2 B): dt больше порога — контекст аннулирован.
     * Доверие в стартовое (кроме SUSPECT — подозрение сильнее тишины,
     * якорь/подозреваемый сохраняются). Первая точка ДВИЖЕНИЯ после тишины
     * идет в ОЖИДАНИЕ ворот C (track-fix 19.09), а не в вечное вердиктное
     * вето: решение откладывается до прихода successors. STAND статика
     * остается закрытой как раньше.
     */
    private fun afterSilence(v: Verdict, dtS: Long): Verdict {
        if (dtS <= SILENCE_RESET_S || v.state == State.SUSPECT) return v
        val next = PrevState(v.next.state, TRUST_START)
        return v.copy(trust = TRUST_START, openFog = v.state == State.MOVING, next = next)
    }

    /** Медиана implied-скоростей соседних пар истории (устойчива к 1 выбросу). */
    internal fun medianImplied(history: List<HistPoint>): Double {
        val speeds = ArrayList<Double>(history.size)
        for (i in 1 until history.size) {
            val a = history[i - 1]; val b = history[i]
            val dt = ((b.time - a.time) / 1000).coerceAtLeast(1)
            speeds.add(FogRepository.haversineM(a.lat, a.lon, b.lat, b.lon) / dt)
        }
        if (speeds.isEmpty()) return 0.0
        speeds.sort()
        return if (speeds.size % 2 == 1) speeds[speeds.size / 2]
        else (speeds[speeds.size / 2 - 1] + speeds[speeds.size / 2]) / 2.0
    }

    /**
     * Максимум implied-скоростей последних [k] сегментов истории
     * (track-fix 19.09): на тонком окне после сброса медиане не из чего
     * считаться, недавний максимум держит разгон. Пусто — 0.0.
     */
    internal fun maxRecentImplied(history: List<HistPoint>, k: Int = 3): Double {
        if (history.size < 2) return 0.0
        var m = 0.0
        var n = 0
        for (i in history.size - 1 downTo 1) {
            if (n >= k) break
            val a = history[i - 1]; val b = history[i]
            val dt = ((b.time - a.time) / 1000).coerceAtLeast(1)
            m = maxOf(m, FogRepository.haversineM(a.lat, a.lon, b.lat, b.lon) / dt)
            n++
        }
        return m
    }

    /** Резкий разворот: угол между соседними отрезками больше порога. */
    internal fun sharpTurn(a: HistPoint, b: HistPoint, c: HistPoint): Boolean {
        val turn = abs(((bearing(b, c) - bearing(a, b)) + 540.0) % 360.0 - 180.0)
        return turn > HEADING_MAX_TURN_DEG
    }

    internal fun bearing(a: HistPoint, b: HistPoint): Double {
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
