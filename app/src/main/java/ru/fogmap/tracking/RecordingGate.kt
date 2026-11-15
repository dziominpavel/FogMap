package ru.fogmap.tracking

/**
 * Решение «поднимать ли запись» (change recording-mode, задача 1.2).
 *
 * Одно место вместо четырёх копий одной и той же проверки (watchdog, ребут,
 * обновление пакета, открытие приложения): расхождение копий — это класс
 * багов, когда один путь стартует при выключенном тумблере, а другой нет.
 *
 * Чистый объект: на вход снимок условий, на выход решение и его имя для лога.
 */
object RecordingGate {

    enum class Decision { START, SKIP_DISABLED, SKIP_PLAY_SERVICES, SKIP_NO_PERMISSION }

    fun decide(recording: Boolean, playServices: Boolean, canTrack: Boolean): Decision = when {
        !recording -> Decision.SKIP_DISABLED
        !playServices -> Decision.SKIP_PLAY_SERVICES
        !canTrack -> Decision.SKIP_NO_PERMISSION
        else -> Decision.START
    }

    /** Исход для диагностики: `started` / `skip_*` (dev-logging, change recording-mode). */
    fun outcomeName(d: Decision): String = when (d) {
        Decision.START -> "started"
        Decision.SKIP_DISABLED -> "skip_disabled"
        Decision.SKIP_PLAY_SERVICES -> "skip_play_services"
        Decision.SKIP_NO_PERMISSION -> "skip_no_permission"
    }
}
