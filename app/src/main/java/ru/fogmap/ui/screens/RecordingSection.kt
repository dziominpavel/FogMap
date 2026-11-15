package ru.fogmap.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.tracking.RecordingReadiness
import ru.fogmap.tracking.RecordingStatus
import ru.fogmap.tracking.TrackingHealth
import ru.fogmap.ui.BgHealthIntents
import ru.fogmap.ui.RecordingControls

/**
 * Секция «Запись» (change recording-mode, 7.2): один тумблер, живой статус и
 * чек-лист фактических разрешений.
 *
 * Отдельный composable, чтобы карта и настройки читали одно и то же состояние
 * (Status) и одну и ту же готовность (Readiness): расхождение «зелёно, а не
 * пишет» — это ровно тот класс бага, который чинили.
 */
@Composable
fun RecordingSection(app: FogMapApp, onDetails: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by app.container.settingsRepository.recordingEnabled.collectAsState(initial = true)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Готовность меняется только действиями в системных экранах: считаем при
    // входе и при возврате (ON_RESUME), а не двумя IPC в секунду.
    var entries by remember { mutableStateOf(RecordingControls.entries(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                entries = RecordingControls.entries(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Секундный тик нужен только возрасту фикса.
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val status = RecordingStatus.of(recording, TrackingHealth.sinceMs(), nowMs)
    val canEnable = RecordingReadiness.canEnable(entries)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Включить запись", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        RecordingStatus.label(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(status)
                    )
                }
                Switch(
                    checked = recording,
                    onCheckedChange = { v ->
                        if (v && !canEnable) {
                            // Включение блокируется, объяснение — в чек-листе ниже.
                            return@Switch
                        }
                        scope.launch { app.container.settingsRepository.setRecordingEnabled(v) }
                    }
                )
            }
            for (e in entries) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (e.level == RecordingReadiness.Level.BLOCKER) "✕ " else
                            if (e.level == RecordingReadiness.Level.WARNING) "! " else "✓ ") + e.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (e.level) {
                            RecordingReadiness.Level.OK -> MaterialTheme.colorScheme.primary
                            RecordingReadiness.Level.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
                            RecordingReadiness.Level.BLOCKER -> MaterialTheme.colorScheme.error
                        }
                    )
                    if (e.level != RecordingReadiness.Level.OK) {
                        TextButton(onClick = { fix(context, e.item) }) { Text("Исправить") }
                    }
                }
            }
            if (onDetails != null) {
                TextButton(onClick = onDetails) {
                    Text("Почему запись может остановиться")
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: RecordingStatus.Snapshot) = when (status.state) {
    RecordingStatus.State.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordingStatus.State.LIVE -> MaterialTheme.colorScheme.primary
    RecordingStatus.State.STALE -> MaterialTheme.colorScheme.error
}

private fun fix(context: Context, item: RecordingReadiness.Item) {
    when (item) {
        RecordingReadiness.Item.ACCURACY,
        RecordingReadiness.Item.BACKGROUND ->
            BgHealthIntents.open(context, listOf(BgHealthIntents.appDetails(context)))
        RecordingReadiness.Item.NOTIFICATIONS ->
            BgHealthIntents.open(context, listOf(BgHealthIntents.notificationSettings(context)))
        RecordingReadiness.Item.BATTERY ->
            BgHealthIntents.open(context, BgHealthIntents.batteryCandidates(context))
        RecordingReadiness.Item.AUTOSTART ->
            BgHealthIntents.open(context, BgHealthIntents.autostartCandidates())
    }
}
