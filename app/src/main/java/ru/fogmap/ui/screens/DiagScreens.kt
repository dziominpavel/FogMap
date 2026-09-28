package ru.fogmap.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fogmap.FogMapApp
import ru.fogmap.diag.DevLog
import ru.fogmap.ui.BottomBar

/**
 * ВРЕМЕННОЕ (dev-logging): экран диагностики.
 * Хвост 50 событий + список файлов лога с Share выбранного + очистка.
 * Удалить вместе с change dev-logging.
 */
@Composable
fun DiagDiagnosticsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    val tail by DevLog.tail.collectAsState()
    var status by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<File>()) }

    // Фоновый перезапуск (fix-track-reliability-0928): без исключения из
    // оптимизации батареи молчат ОБА канала watchdog, и по логу это не
    // отличить от «будильник не сработал». Читаем при каждом ON_RESUME —
    // возвращаемся из системных настроек сразу с новым значением.
    val pm = remember(context) {
        context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    }
    var batteryIgnored by remember { mutableStateOf<Boolean?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = runCatching {
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                }.getOrDefault(false)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun requestBatteryIgnore() {
        val uri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        runCatching { context.startActivity(intent) }
            .onFailure { status = "Не удалось открыть настройки батареи" }
    }

    fun refreshFiles() {
        scope.launch {
            files = withContext(Dispatchers.IO) {
                app.devLogFile?.allFiles() ?: emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { refreshFiles() }

    fun share(file: File?) {
        if (file == null || !file.exists()) {
            status = "Файл лога пуст"
            return
        }
        scope.launch {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.devlog", file)
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
            }.onFailure {
                status = "Не удалось открыть выбор приложения"
            }
        }
    }

    Scaffold(bottomBar = { BottomBar(nav, "settings") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    share(
                        app.devLogFile?.currentFile()?.takeIf { it.exists() }
                            ?: files.maxByOrNull { it.name }
                    )
                }) { Text("Поделиться") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            app.devLogFile?.clearAll() ?: 0
                        }
                        status = "Очищено файлов: $n"
                        DevLog.i("DevDiag", "logs_cleared", mapOf("files" to n))
                        files = emptyList()
                    }
                }) { Text("Очистить логи") }
                TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Фоновый перезапуск", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (batteryIgnored) {
                            true -> "Батарея: оптимизация игнорируется, каналам не мешает"
                            false -> "Батарея: приложение может быть заморожено — " +
                                "перезапуск в фоне молчит обоими каналами"
                            null -> "Батарея: состояние не прочитано"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { requestBatteryIgnore() }) {
                        Text("Разрешить работать в фоне")
                    }
                }
            }
            Text("Файлы лога", style = MaterialTheme.typography.titleSmall)
            if (files.isEmpty()) {
                Text(
                    "Файлов лога нет — поделитесь после первых событий",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files, key = { it.name }) { f ->
                        LogFileRow(f) { share(f) }
                    }
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

/** Строка списка: имя файла, размер, время изменения; тап = Share этого файла. */
@Composable
private fun LogFileRow(file: File, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(file.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${file.length() / 1024} КБ · ${formatMtime(file.lastModified())}",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
    }
}

private fun formatMtime(ms: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(ms))
}.getOrDefault("—")
