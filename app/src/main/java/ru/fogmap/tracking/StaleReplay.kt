package ru.fogmap.tracking

/**
 * Метка приёма фикса после заморозки процесса (field-hardening 1.3).
 *
 * Замороженный процесс получает накопленную очередь колбэков разом: все
 * фиксы приходят в одну миллисекунду, а `Location.getTime()` у них старые.
 * Доверие считает зазоры по моменту приёма (`recv`), поэтому пачка
 * выглядела как «35 точек за 0 секунд» — прыжки и телепорты, принятой
 * оставалась одна точка из 35.
 *
 * Правило: подозрительно старый фикс, идущий ПО ВРЕМЕНИ ПОСЛЕ предыдущего,
 * получает `recv = loc.time` — последовательность восстанавливается с
 * реальными зазорами и проходит обычные ворота доверия. Живые фиксы и
 * дубли меток провайдера остаются на текущем времени.
 */
object StaleReplay {

    /** Фикс старше этого возраста считается отложенным заморозкой. */
    const val STALE_MS = 60_000L

    fun recvFor(nowMs: Long, fixMs: Long, lastRecvMs: Long?): Long {
        if (fixMs <= 0L || fixMs > nowMs) return nowMs
        if (nowMs - fixMs <= STALE_MS) return nowMs
        // Провайдер повторяет метку фикса: назад по времени не идём.
        if (lastRecvMs != null && fixMs <= lastRecvMs) return nowMs
        return fixMs
    }

    /** Был ли фикс переигран (для счётчика в `flush`). */
    fun replayed(nowMs: Long, recvMs: Long): Boolean = recvMs != nowMs
}
