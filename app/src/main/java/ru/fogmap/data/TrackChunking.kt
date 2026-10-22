package ru.fogmap.data

/**
 * Нарезка принятых точек на непрерывные отрезки (fix-track-gap-visibility 3.3).
 *
 * Единый источник порога для живой записи ([FogRepository.appendPoints]) и
 * пересчёта из raw ([TrackDebugImport.chunkAccepted]): иначе live и rebuild
 * дают разные `time_s` на одном и том же наборе точек.
 *
 * Разрыв режет отрезок: тишина/заморозка не должна попадать во время движения.
 * Порог 120 с выбран по живым интервалам доставки (5–60 с) — пауза на
 * светофоре не режется, а многочасовая дыра не впитывается.
 *
 * Чистый объект, unit-тестируется без БД.
 */
object TrackChunking {

    /** Предел точек в отрезке: лимит переменных SQLite и порядок живого flush. */
    const val MAX_COUNT = 20

    /** Максимальный спан непрерывного отрезка, мс. */
    const val MAX_SPAN_MS = 120_000L

    /**
     * Индексы начал отрезков. Правило одно на оба потребителя: новый отрезок
     * начинается, когда в текущем уже [maxCount] точек или спан от его первой
     * точки превысил [maxSpanMs].
     */
    fun starts(
        times: List<Long>,
        maxCount: Int = MAX_COUNT,
        maxSpanMs: Long = MAX_SPAN_MS
    ): List<Int> {
        if (times.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        out.add(0)
        for (i in 1 until times.size) {
            val start = out.last()
            if (i - start >= maxCount || times[i] - times[start] > maxSpanMs) out.add(i)
        }
        return out
    }

    fun chunks(
        points: List<RawPoint>,
        maxCount: Int = MAX_COUNT,
        maxSpanMs: Long = MAX_SPAN_MS
    ): List<List<RawPoint>> {
        val s = starts(points.map { it.time }, maxCount, maxSpanMs)
        if (s.isEmpty()) return emptyList()
        return s.mapIndexed { idx, from ->
            val to = if (idx + 1 < s.size) s[idx + 1] else points.size
            points.subList(from, to).toList()
        }
    }

    /**
     * Суммарный спан отрезков, мс: сумма (последняя - первая) внутри каждого
     * отрезка. Разрывы между отрезками не входят. Отрицательный спан
     * (инверсия времени после разморозки) не вычитается из суммы.
     */
    fun totalSpanMs(
        times: List<Long>,
        maxCount: Int = MAX_COUNT,
        maxSpanMs: Long = MAX_SPAN_MS
    ): Long {
        val s = starts(times, maxCount, maxSpanMs)
        if (s.isEmpty()) return 0L
        var total = 0L
        for (idx in s.indices) {
            val from = s[idx]
            val to = if (idx + 1 < s.size) s[idx + 1] else times.size
            total += (times[to - 1] - times[from]).coerceAtLeast(0L)
        }
        return total
    }
}
