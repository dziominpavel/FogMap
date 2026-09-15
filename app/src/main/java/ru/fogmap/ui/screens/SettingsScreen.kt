package ru.fogmap.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.ui.BottomBar

/** Настройки (spec app-shell, задача 5.3): полный сброс + «О программе». */
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    Scaffold(bottomBar = { BottomBar(nav, "settings") }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("Настройки")
            Button(onClick = { confirmReset = true }) { Text("Сбросить весь прогресс") }
            Button(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://yandex.ru/legal/maps_termsofuse/".toUri())
                )
            }) { Text("О программе (условия Яндекс Карт)") }
            if (done) Text("Прогресс сброшен")
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
