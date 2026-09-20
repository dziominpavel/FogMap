package ru.fogmap.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import ru.fogmap.diag.DevLog
import ru.fogmap.ui.BottomBar

/**
 * ВРЕМЕННОЕ (dev-logging): экран диагностики.
 * Хвост 50 событий + Share сегодняшнего JSONL + очистка. Удалить вместе с change.
 */
@Composable
fun DiagDiagnosticsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    val tail by DevLog.tail.collectAsState()
    var status by remember { mutableStateOf("") }
    // Эко-режим (battery-eco 1.1): дев-переключалка base/eco, трек не сбрасывает.
    val prefs by app.container.dataStore.data.collectAsState(
        initial = androidx.datastore.preferences.core.preferencesOf()
    )
    val ecoMode = prefs[PrefsKeys.ECO_MODE] ?: false

    Scaffold(bottomBar = { BottomBar(nav, "settings") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            app.devLogFile?.currentFile()?.takeIf { it.exists() }
                                ?: app.devLogFile?.allFiles()?.maxByOrNull { it.name }
                        }
                        if (file == null || !file.exists()) {
                            status = "Файл лога пуст"
                            return@launch
                        }
                        val uri = runCatching {
                            FileProvider.getUriForFile(
                                context, "${context.packageName}.devlog", file
                            )
                        }.getOrNull()
                        if (uri == null) {
                            status = "Не удалось построить URI"
                            return@launch
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "Поделиться логом"))
                        }
                    }
                }) { Text("Поделиться") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            app.devLogFile?.clearAll() ?: 0
                        }
                        status = "Очищено файлов: $n"
                        DevLog.i("DevDiag", "logs_cleared", mapOf("files" to n))
                    }
                }) { Text("Очистить логи") }
                TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Эко-режим (base/eco)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (ecoMode) "eco: STANDBY/BURST активны"
                            else "base: плотный HIGH как раньше",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = ecoMode,
                        onCheckedChange = { v ->
                            scope.launch {
                                app.container.settingsRepository.setEcoMode(v)
                                status = if (v) "Эко включен" else "Эко выключен (base)"
                            }
                        }
                    )
                }
            }
            Text(
                "Хвост ${tail.size} событий, сессия ${DevLog.session}",
                style = MaterialTheme.typography.titleSmall
            )
            Card(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.padding(8.dp)) {
                    items(tail) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
