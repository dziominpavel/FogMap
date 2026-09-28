package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Второй канал watchdog (fix-track-reliability-0928): будильник срабатывает,
 * даже когда периодическая работа WorkManager приостановлена OEM'ом — именно
 * так 28.09 молчали 13 циклов подряд.
 *
 * `goAsync` + отдельный диспетчер: проверка читает DataStore, а onReceive
 * идет в главном потоке. После отработки будильник перепланируется, цепочка
 * живет, пока система вообще дает срабатывать.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                TrackingWatchdog.checkAndStart(appContext, source = "alarm")
            } finally {
                runCatching { TrackingWatchdog.scheduleAlarm(appContext) }
                pending.finish()
            }
        }
    }
}
