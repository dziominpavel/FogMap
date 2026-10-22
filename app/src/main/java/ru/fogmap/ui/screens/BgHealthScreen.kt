package ru.fogmap.ui.screens

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import ru.fogmap.tracking.BgHealth
import ru.fogmap.tracking.TrackingLife
import ru.fogmap.ui.BgHealthIntents
import ru.fogmap.ui.BottomBar

/**
 * Проверка фоновой записи (fix-track-gap-visibility 5.1–5.4).
 *
 * Экран ИЗМЕРЯЕТ состояние и показывает конкретные причины, вместо инструкций:
 * возраст последней точки и heartbeat, состояние уведомлений, батареи и
 * причину последней остановки процесса из системного журнала. Разрешений не
 * просит и настройки сам не меняет — только открывает нужный системный экран
 * по кнопке «Исправить».
 */
@Composable
fun BgHealthScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    val prefs by app.container.dataStore.data.collectAsState(initial = preferencesOf())
    val paused = prefs[PrefsKeys.PAUSED] ?: false
    var snapshot by remember { mutableStateOf<BgHealth.Snapshot?>(null) }
    var checkedAt by remember { mutableLongStateOf(0L) }

    suspend fun measure() {
        val now = System.currentTimeMillis()
        val lastPoint = withContext(Dispatchers.IO) {
            runCatching { app.container.db.trackDao().lastPointTime() }.getOrNull()
        }
        val heartbeat = TrackingLife.lastHeartbeatWallMs().takeIf { it > 0L }
        val exit = withContext(Dispatchers.IO) {
            runCatching { TrackingLife.readLastExit(context)?.let(BgHealth::exitFrom) }.getOrNull()
        }
        snapshot = BgHealth.Snapshot(
            paused = paused,
            lastPointAgeMs = BgHealth.ageMs(now, lastPoint),
            lastHeartbeatAgeMs = BgHealth.ageMs(now, heartbeat),
            notificationsEnabled = BgHealthIntents.notificationsEnabled(context),
            batteryRestricted = batteryRestricted(context),
            lastExit = exit
        )
        checkedAt = now
    }

    LaunchedEffect(paused) { measure() }
    // Возврат из системных настроек — сразу новое состояние, без лишней кнопки.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { measure() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(bottomBar = { BottomBar(nav, "settings") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Фоновая запись", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { measure() } }) { Text("Проверить") }
                TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
            }
            val s = snapshot
            if (s == null) {
                Text("Проверяем…", style = MaterialTheme.typography.bodyMedium)
            } else {
                SummaryCard(s, checkedAt)
                val problems = BgHealth.problems(s)
                if (problems.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Проблем не найдено: запись в фоне настроена.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text("Что мешает записи", style = MaterialTheme.typography.titleMedium)
                    for (p in problems) {
                        ProblemCard(
                            problem = p,
                            snapshot = s,
                            onFix = { onFix(context, p) },
                            onRecheck = { scope.launch { measure() } }
                        )
                    }
                }
                s.lastExit?.takeIf { !it.systemStop }?.let { InfoExitCard(it) }
                Text(
                    "Проверка только читает состояние: разрешения не запрашиваются, " +
                        "настройки не меняются.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(s: BgHealth.Snapshot, checkedAt: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    s.paused -> "Запись на паузе"
                    BgHealth.isRecording(s) -> "Запись идёт"
                    else -> "Запись не идёт"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Последняя точка: ${ageText(s.lastPointAgeMs)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Последний heartbeat: ${ageText(s.lastHeartbeatAgeMs)}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (checkedAt > 0L) {
                Text(
                    "Проверено: ${timeText(checkedAt)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ProblemCard(
    problem: BgHealth.Problem,
    snapshot: BgHealth.Snapshot,
    onFix: () -> Unit,
    onRecheck: () -> Unit
) {
    val title: String
    val detail: String
    when (problem) {
        BgHealth.Problem.NOTIFICATIONS -> {
            title = "Уведомления выключены"
            detail = "Уведомление «FogMap пишет трек» не видно в шторке — " +
                "система может не считать сервис активным."
        }
        BgHealth.Problem.BATTERY -> {
            title = "Оптимизация батареи"
            detail = "Приложение не исключено из оптимизации: в фоне система " +
                "может останавливать запись."
        }
        BgHealth.Problem.KILLED -> {
            val e = snapshot.lastExit
            title = "Система останавливала приложение"
            detail = "Последняя остановка: ${e?.reason ?: "?"} ${ageText(e?.ageMs)} назад" +
                (e?.vendor?.let { " ($it)" } ?: "") +
                ". Пока автозапуск и батарея не разрешены, запись не возобновится сама."
        }
        BgHealth.Problem.NO_RECENT_POINTS -> {
            title = "Нет свежих точек"
            detail = "Последняя точка ${ageText(snapshot.lastPointAgeMs)} назад — " +
                "запись сейчас не пополняется."
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (problem == BgHealth.Problem.NO_RECENT_POINTS) {
                    OutlinedButton(onClick = onRecheck) { Text("Проверить снова") }
                } else {
                    Button(onClick = onFix) { Text("Исправить") }
                }
            }
        }
    }
}

@Composable
private fun InfoExitCard(exit: BgHealth.Exit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Последняя остановка процесса", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${exit.reason} ${ageText(exit.ageMs)} назад" +
                    (exit.vendor?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Переход к исправлению: каждый интент резолвится, иначе — настройки приложения. */
private fun onFix(context: Context, problem: BgHealth.Problem) {
    when (problem) {
        BgHealth.Problem.NOTIFICATIONS ->
            BgHealthIntents.open(context, listOf(BgHealthIntents.notificationSettings(context)))
        BgHealth.Problem.BATTERY ->
            BgHealthIntents.open(context, BgHealthIntents.batteryCandidates(context))
        BgHealth.Problem.KILLED, BgHealth.Problem.NO_RECENT_POINTS ->
            BgHealthIntents.open(context, BgHealthIntents.autostartCandidates())
    }
}

private fun batteryRestricted(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return runCatching {
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}

private fun ageText(ageMs: Long?): String {
    if (ageMs == null) return "нет данных"
    val s = ageMs / 1000
    return when {
        s < 60 -> "только что"
        s < 3600 -> "${s / 60} мин"
        s < 86_400 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        else -> "${s / 86_400} д ${(s % 86_400) / 3600} ч"
    }
}

private fun timeText(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
