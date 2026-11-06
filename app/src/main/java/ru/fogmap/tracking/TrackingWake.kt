package ru.fogmap.tracking

import android.content.Context
import android.os.PowerManager

/**
 * Wake-lock на время движения (field-hardening 1.1).
 *
 * Полевой разбор 06.11: сервис в foreground, батарея в whitelist, но цикл
 * записи тикал ~1 ч из 24 — система не планировала процесс, и участки
 * движения терялись (35 `LIFE/freeze_gap`, максимум 2 ч 30 мин). FGS без
 * wake-lock на Huawei не держит CPU: таймеры `delay()` считают uptime,
 * который не идёт во сне.
 *
 * Держим CPU ТОЛЬКО в профилях движения (ACTIVE/BURST): стоянка остаётся
 * экономной, сон устройства не ломается. Пауза снимает lock.
 */
object TrackingWake {

    /** Имя в `dumpsys power` — по нему lock ищется в поле. */
    const val TAG = "FogMap:tracking"

    /** Нужен ли wake-lock: только пока пишется движение и нет паузы. */
    fun neededFor(profile: EcoGovernor.Profile, paused: Boolean): Boolean =
        !paused && (profile == EcoGovernor.Profile.ACTIVE || profile == EcoGovernor.Profile.BURST)

    /**
     * Держатель на один сервис. Идемпотентен: повторное включение не плодит
     * ссылки (setReferenceCounted(false)), выключение без включения молчит.
     */
    class Holder(private val context: Context) {

        private var lock: PowerManager.WakeLock? = null

        /** Возвращает фактическое состояние lock после вызова. */
        fun setHeld(held: Boolean): Boolean {
            if (held) {
                val l = lock ?: create() ?: return false
                if (!l.isHeld) runCatching { l.acquire() }
                return runCatching { l.isHeld }.getOrDefault(false)
            }
            lock?.let { l -> if (l.isHeld) runCatching { l.release() } }
            return false
        }

        private fun create(): PowerManager.WakeLock? = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.also {
                it.setReferenceCounted(false)
                lock = it
            }
        }.getOrNull()
    }
}
