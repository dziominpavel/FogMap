package ru.fogmap.tracking

/**
 * Чек-лист готовности записи (change recording-mode, D9).
 *
 * Проверяется ФАКТИЧЕСКОЕ состояние разрешений, а не факт их запроса: иначе
 * получается «зелёно, а записи нет». Обязательные пункты блокируют включение,
 * предупреждения — нет.
 *
 * Чистый объект: снимок системного состояния приходит аргументами.
 */
object RecordingReadiness {

    enum class Item { ACCURACY, BACKGROUND, NOTIFICATIONS, BATTERY, AUTOSTART }
    enum class Level { OK, BLOCKER, WARNING }

    data class Entry(val item: Item, val level: Level, val title: String)

    fun evaluate(
        fineLocation: Boolean,
        backgroundLocation: Boolean,
        notifications: Boolean,
        batteryExempt: Boolean,
        autostartKnown: Boolean = false
    ): List<Entry> = listOf(
        Entry(
            Item.ACCURACY,
            if (fineLocation) Level.OK else Level.BLOCKER,
            "Точная геолокация"
        ),
        Entry(
            Item.BACKGROUND,
            if (backgroundLocation) Level.OK else Level.BLOCKER,
            "Фоновая геолокация («Разрешить всегда»)"
        ),
        Entry(
            Item.NOTIFICATIONS,
            if (notifications) Level.OK else Level.WARNING,
            "Уведомления"
        ),
        Entry(
            Item.BATTERY,
            if (batteryExempt) Level.OK else Level.WARNING,
            "Без оптимизации батареи"
        ),
        Entry(
            Item.AUTOSTART,
            if (autostartKnown) Level.OK else Level.WARNING,
            "Автозапуск в настройках системы"
        )
    )

    /** Включать запись можно только без обязательных провалов. */
    fun canEnable(entries: List<Entry>): Boolean =
        entries.none { it.level == Level.BLOCKER }

    /** Короткая причина отказа для строки статуса. */
    fun blockerTitles(entries: List<Entry>): List<String> =
        entries.filter { it.level == Level.BLOCKER }.map { it.title }
}
