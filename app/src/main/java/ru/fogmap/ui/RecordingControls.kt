package ru.fogmap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import ru.fogmap.tracking.RecordingGate
import ru.fogmap.tracking.RecordingReadiness
import ru.fogmap.tracking.TrackingPreconditions
import ru.fogmap.tracking.TrackingService

/**
 * Единственный вход «включить запись» и «поднять запись» (change
 * recording-mode, D9/D10).
 *
 * Готовность и запуск живут здесь, а не в каждом экране: иначе тумблер в
 * Настройках, плашка на карте и «Готово» онбординга разойдутся по условиям —
 * ровно то, что ревью нашло в первом варианте.
 */
object RecordingControls {

    /** Фактическое состояние разрешений и настроек системы. */
    fun entries(context: Context): List<RecordingReadiness.Entry> =
        RecordingReadiness.evaluate(
            fineLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT < 29 ||
                granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = BgHealthIntents.notificationsEnabled(context),
            batteryExempt = isBatteryExempt(context)
        )

    /** Можно ли включить запись: обязательные пункты чек-листа выполнены. */
    fun canEnable(context: Context): Boolean =
        RecordingReadiness.canEnable(entries(context))

    /**
     * Поднимает службу, если запись включена и условия выполнены.
     * Возвращает true, если попытка старта была предпринята.
     */
    fun ensureStarted(context: Context, recording: Boolean, via: String): Boolean {
        val decision = RecordingGate.decide(
            recording = recording,
            playServices = TrackingPreconditions.playServicesAvailable(context),
            canTrack = TrackingService.canTrack(context)
        )
        return decision == RecordingGate.Decision.START &&
            TrackingService.start(context, via = via)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }
}
