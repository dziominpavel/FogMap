package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.diag.DevLog

/**
 * Приём фиксов вне процесса сервиса (fix-journal D1).
 *
 * Fused доставляет результат в PendingIntent, система сама поднимает процесс
 * на broadcast и держит wakelock на время приёма. Здесь нельзя делать ничего
 * тяжёлого и нельзя стартовать foreground-сервис из фона (Android 12+):
 * только запись в журнал и заявка на обработку.
 */
class LocationFixReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIX) return
        val result = runCatching { LocationResult.extractResult(intent) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val envelopes = result.locations.map { loc ->
            FixEnvelope.of(
                time = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                acc = if (loc.hasAccuracy()) loc.accuracy else -1f,
                speed = if (loc.hasSpeed()) loc.speed else null,
                isMock = isMock(loc),
                recvMs = now
            )
        }
        if (envelopes.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? FogMapApp
                if (app == null) {
                    DevLog.w("TRACK", "inbox_skip", mapOf("reason" to "no_app"))
                } else {
                    runCatching {
                        FixInboxWriter.write(app.container.db, envelopes)
                        FixInboxWriter.prune(app.container.db, now)
                    }.onFailure {
                        DevLog.w(
                            "TRACK", "inbox_write_failed",
                            mapOf(
                                "n" to envelopes.size,
                                "err" to (it.javaClass.simpleName + ": " + (it.message ?: "")).take(200)
                            )
                        )
                    }
                    InboxWork.enqueue(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider

    companion object {
        const val ACTION_FIX = "ru.fogmap.tracking.FIX"
    }
}
