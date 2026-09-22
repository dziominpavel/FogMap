package ru.fogmap.ui.screens

import ru.fogmap.tracking.TrustEngine

/**
 * Гейт правдоподобия и состояние чипа геолокации (change first-launch-visibility).
 * Чистые функции без Android-зависимостей — покрыты unit-тестами.
 * Пороги и переходы зафиксированы в спеке `map-render` (delta этого change):
 * фикс хуже 100 м не двигает камеру/курсор/показную дырку; чип объясняет,
 * почему живой точки на экране ещё нет.
 */
object GeoStatus {

    /** Порог правдоподобия экранного фикса, метры (спека map-render). */
    const val PLAUSIBLE_ACC_M = 100f

    /** Состояния чипа состояния геолокации (спека map-render). */
    enum class State { GEO_OFF, SEARCHING, FOUND, STALE }

    /**
     * Правдоподобен ли фикс как экранный источник: accuracy есть, строго
     * положительна и не хуже [PLAUSIBLE_ACC_M]. Отсутствие accuracy, ноль
     * и отрицательное значение (баговые фиксы) гейт не проходят.
     */
    fun plausible(acc: Float?): Boolean =
        acc != null && acc > 0f && acc <= PLAUSIBLE_ACC_M

    /**
     * Состояние чипа. Приоритеты: гео выключено > фикса не было > протухло >
     * точка получена.
     *
     * @param geoEnabled мастер-переключатель геолокации (проверка провайдеров).
     * @param lastFixMs отметка последнего правдоподобного фикса, mono-время;
     *   null — правдоподобного фикса ещё не было.
     * @param nowMs текущая mono-временная метка.
     * Порог протухания равен [TrustEngine.SILENCE_RESET_S] — тот же 2-минутный
     * контракт тишины, что у протухания нативной точки.
     */
    fun state(geoEnabled: Boolean, lastFixMs: Long?, nowMs: Long): State = when {
        !geoEnabled -> State.GEO_OFF
        lastFixMs == null -> State.SEARCHING
        nowMs - lastFixMs > TrustEngine.SILENCE_RESET_S * 1000L -> State.STALE
        else -> State.FOUND
    }

    /** Текст чипа (язык UI проекта — русский). */
    fun label(s: State): String = when (s) {
        State.GEO_OFF -> "Гео выключено"
        State.SEARCHING -> "Ищу спутники…"
        State.FOUND -> "Точка получена"
        State.STALE -> "Протухло"
    }
}
