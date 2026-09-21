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
     * Окно хвоста TAIL_LIMIT покрывает максимальный живой батч (FLUSH_SIZE)
     * плюс перенос: иначе чанки rebuild сиротили pending (track-fix 19.09:
     * 214 висячих точек) — adjudication видит весь хвост целиком.
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
    /**
     * Окно хвоста из БД: с запасом покрывает FLUSH_SIZE + перенос лага,
     * иначе крупные батчи (rebuild-чанки) сиротили бы pending вечно.
     */
    const val TAIL_LIMIT = 32

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
        // Хвост очереди ждет будущего и в этом вызове не трогается.
        val actionable = if (pendingAsc.size > LAG) pendingAsc.dropLast(LAG) else emptyList()
        var anch = anchor
        for (c in actionable) {
            val out = anch?.let { FogRepository.haversineM(it.lat, it.lon, c.lat, c.lon) } ?: 0.0
            val back = anch?.let { FogRepository.haversineM(it.lat, it.lon, newest.lat, newest.lon) } ?: 0.0
            val roundTrip = anch != null &&
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
                anch = c
            }
        }
        return Result(confirmed, vetoed, reasons)
    }
}
