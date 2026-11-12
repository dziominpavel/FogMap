package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.fogmap.diag.DevLog

/**
 * Снятие уведомления записи (motion-state, поле 12.11).
 *
 * Владелец смахивал уведомление «потому что мешает» — и запись вставала на
 * 2,5 суток. Канал переведён в IMPORTANCE_MIN и уведомление несмахиваемое,
 * но на части прошивок это не соблюдается: страховка возвращает трекинг.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DevLog.w("TRACK", "notif_dismissed", emptyMap())
        runCatching { TrackingWatchdog.checkAndStart(context, source = "notif") }
    }
}
