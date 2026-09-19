package ru.fogmap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ru.fogmap.BuildConfig
import ru.fogmap.FogMapApp
import ru.fogmap.data.ThemeModes
import ru.fogmap.ui.BottomBar

/** Настройки (ui-dark-redesign 3.3): секции + тема + опасная зона + о программе. */
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val prefs by app.container.dataStore.data.collectAsState(initial = preferencesOf())
    val themeMode = prefs[ru.fogmap.data.PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT
    val paused = prefs[ru.fogmap.data.PrefsKeys.PAUSED] ?: false
    // ВРЕМЕННОЕ (dev-logging): тумблер диагностики.
    val diagEnabled = prefs[ru.fogmap.data.PrefsKeys.DIAG_ENABLED] ?: true
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: BuildConfig.VERSION_NAME.takeIf { !it.isNullOrBlank() } ?: "—"
    }

    Scaffold(bottomBar = { BottomBar(nav, "settings") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Внешний вид ---
            Text("Внешний вид", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Тема", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    val options = listOf(
                        ThemeModes.DARK to "Тёмная",
                        ThemeModes.LIGHT to "Светлая",
                        ThemeModes.SYSTEM to "Система"
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = {
                                    scope.launch {
                                        app.container.settingsRepository.setThemeMode(mode)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
            // --- Запись (trust-v2 4.1: единственное место паузы) ---
            Text("Запись", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Пауза «не писать»", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (paused) "Запись остановлена"
                            else "Трекинг идет всегда, когда разрешен",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = paused,
                        onCheckedChange = { v ->
                            scope.launch { app.container.settingsRepository.setPaused(v) }
                        }
                    )
                }
            }
            // --- Диагностика (ВРЕМЕННОЕ, dev-logging: удалить вместе с change) ---
            Text("Диагностика (временно)", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Подробные логи", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "CAMERA/RENDER/PERF/UI/TRACK, TTL сутки",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = diagEnabled,
                            onCheckedChange = { v ->
                                scope.launch { app.container.settingsRepository.setDiagEnabled(v) }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { nav.navigate("diagnostics") }) {
                        Text("Открыть диагностику")
                    }
                }
            }
            // --- Данные / опасная зона ---
            Text("Данные", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Опасная зона: сброс удалит ячейки тумана, треки и статистику",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Сбросить весь прогресс") }
                    if (done) {
                        Spacer(Modifier.height(8.dp))
                        Text("Прогресс сброшен")
                    }
                }
            }
            // --- О программе ---
            Text("О программе", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("FogMap $version", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Сборка: ${BuildConfig.BUILD_TIME}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Трекер тумана войны. Все данные хранятся только на телефоне.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.container.settingsRepository.resetAll(app.container.fogRepository)
                        confirmReset = false
                        done = true
                    }
                }) { Text("Сбросить всё") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
            text = { Text("Удалятся ячейки тумана, треки и статистика. Продолжить?") }
        )
    }
}
