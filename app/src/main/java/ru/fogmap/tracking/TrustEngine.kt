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
        val next: PrevState
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

        // 3. Прыжок по скорости: потолок зависит от состояния и серии.
        // Короткая история (< 3 точек) — контекст неизвестен (например, рестарт
        // сервиса на трассе): мягкий потолок, иначе любой старт = ложный jump.
        val cap = when {
            prev?.state == State.MOVING || prev?.state == State.SUSPECT ->
                maxOf(MOVING_CAP_MIN_MS, MOVING_CAP_FACTOR * medianImplied(history))
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
                    if (implied > 0.5) {
                        val open = trust >= FogGrid.TRUST_OPEN
                        Verdict(State.MOVING, trust, open, null, PrevState(State.MOVING, trust))
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
     * Доверие в стартовое, первая точка туман не открывает (кроме SUSPECT —
     * подозрение сильнее тишины, якорь/подозреваемый сохраняются).
     */
    private fun afterSilence(v: Verdict, dtS: Long): Verdict {
        if (dtS <= SILENCE_RESET_S || v.state == State.SUSPECT) return v
        val next = PrevState(v.next.state, TRUST_START)
        return v.copy(trust = TRUST_START, openFog = false, next = next)
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
