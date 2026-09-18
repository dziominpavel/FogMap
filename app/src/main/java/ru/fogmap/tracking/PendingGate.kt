package ru.fogmap.tracking

import ru.fogmap.data.FogRepository

/**
 * Отложенное открытие тумана (trust-v2 C, ворота C).
 *
 * Туман необратим, а все ворота A/B — причинные (знают только прошлое).
 * Поэтому точка открывается не на записи, а после подтверждения 2–3
 * successors ([LAG]): возврат в якорь до открытия дает вето, и короткие
 * круговые вылеты не открывают вообще ничего.
 *
 * Чистый объект без состояния: вход — хвост ожидания oldest-first, якорь
 * (последняя подтвержденная точка) и свежая точка; выход — списки
 * confirmed/vetoed. Последние [LAG] точек всегда остаются в ожидании.
 * Сервис/репозиторий только хранят очередь между вызовами.
 * Полностью покрыт unit-тестами.
 */
object PendingGate {
    /** Лаг подтверждения, доставки (~20–30 сек тумана позади реальности). */
    const val LAG = 2
    /** Ближе якоря — не вылет, вето не применяется. */
    const val VETO_MIN_DIST_M = 200.0
    /** Старше — протухла (ночная статика утром не открывается). */
    const val MAX_PENDING_AGE_S = 600L
    /** Окно хвоста из БД (больше не бывает: каждый flush разбирает до лага). */
    const val TAIL_LIMIT = 16

    data class Item(val id: Long, val lat: Double, val lon: Double, val time: Long)

    data class Result(val confirmed: List<Item>, val vetoed: List<Item>)

    fun adjudicate(
        pendingAsc: List<Item>,
        anchor: Item?,
        newest: Item
    ): Result {
        val confirmed = ArrayList<Item>()
        val vetoed = ArrayList<Item>()
        // Хвост очереди ждет будущего и в этом вызове не трогается.
        val actionable = if (pendingAsc.size > LAG) pendingAsc.dropLast(LAG) else emptyList()
        var anch = anchor
        for (c in actionable) {
            val out = anch?.let { FogRepository.haversineM(it.lat, it.lon, c.lat, c.lon) } ?: 0.0
            val back = anch?.let { FogRepository.haversineM(it.lat, it.lon, newest.lat, newest.lon) } ?: 0.0
            val roundTrip = anch != null &&
                out > VETO_MIN_DIST_M &&
                back < TrustEngine.RETURN_RATIO * out
            val stale = (newest.time - c.time) / 1000 > MAX_PENDING_AGE_S
            if (roundTrip || stale) {
                vetoed.add(c)
            } else {
                confirmed.add(c)
                anch = c
            }
        }
        return Result(confirmed, vetoed)
    }
}
