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
    /**
     * Лаг подтверждения, доставки (~20–30 сек тумана позади реальности).
     * Последние [LAG] точек ждут будущего, НО точка из этого хвоста старше
     * [MAX_PENDING_AGE_S] относительно новейшей точки входит в разбор и без
     * будущих доставок (дренаж по wall-clock) — ничего не висит вечно.
     */
    const val LAG = 2
    /** Ближе якоря — не вылет, вето не применяется. */
    const val VETO_MIN_DIST_M = 200.0
    /**
     * Старше — протухла (ночная статика утром не открывается).
     * Применяется только к не-MOVING точкам (fix-eco-signal-loss 2.1):
     * движение после обрыва доставки подтверждается, а не ветируется
     * по возрасту.
     */
    const val MAX_PENDING_AGE_S = 600L

    data class Item(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val time: Long,
        /**
         * Вердикт точки (STAND/MOVING/SUSPECT). null — старые вызовы/якорь:
         * трактуется как не-MOVING (протухание применяется).
         */
        val state: String? = null
    )

    data class Result(
        val confirmed: List<Item>,
        val vetoed: List<Item>,
        /**
         * Аудит вето (track-fix 19.09): id точки -> причина. Пишется
         * в rejectReason точки, счетчики не трогает.
         */
        val vetoedReasons: Map<Long, String> = emptyMap()
    )

    fun adjudicate(
        pendingAsc: List<Item>,
        anchor: Item?,
        newest: Item
    ): Result {
        val confirmed = ArrayList<Item>()
        val vetoed = ArrayList<Item>()
        val reasons = HashMap<Long, String>()
        // Хвост очереди ждет будущего и в этом вызове не трогается — но
        // протухшая по wall-clock точка (старше MAX_PENDING_AGE_S от новейшей)
        // входит в разбор даже из последних LAG позиций: иначе хвост висит
        // вечно, когда новых доставок больше нет (2 точки 19:10 25.09).
        val actionable = pendingAsc.filterIndexed { i, c ->
            i < pendingAsc.size - LAG ||
                (newest.time - c.time) / 1000 > MAX_PENDING_AGE_S
        }
        var anch = anchor
        for (c in actionable) {
            val out = anch?.let { FogRepository.haversineM(it.lat, it.lon, c.lat, c.lon) } ?: 0.0
            val back = anch?.let { FogRepository.haversineM(it.lat, it.lon, newest.lat, newest.lon) } ?: 0.0
            // Временная монотонность (fix-pending-gate-false-vetoes D1):
            // «туда-обратно» имеет смысл только когда кандидат НОВЕЕ якоря —
            // иначе морозка превращает маршрут в ложный круговой вылет против
            // более нового якоря (49 вето 25.09). Якорь продвигается только
            // вперед по времени.
            val roundTrip = anch != null &&
                c.time > anch.time &&
                out > VETO_MIN_DIST_M &&
                back < TrustEngine.RETURN_RATIO * out
            // Протухание — только для не-MOVING (fix-eco-signal-loss 2.1):
            // хвост движения после морозки процесса не должен пропадать.
            val stale = c.state != TrustEngine.State.MOVING.name &&
                (newest.time - c.time) / 1000 > MAX_PENDING_AGE_S
            if (roundTrip || stale) {
                vetoed.add(c)
                reasons[c.id] = if (stale) FogRepository.VETO_STALE else FogRepository.VETO_RETURN
            } else {
                confirmed.add(c)
                if (anch == null || c.time > anch.time) anch = c
            }
        }
        return Result(confirmed, vetoed, reasons)
    }
}
