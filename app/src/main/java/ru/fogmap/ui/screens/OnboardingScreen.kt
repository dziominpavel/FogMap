package ru.fogmap.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.tracking.TrackingService

/**
 * Онбординг разрешений (spec tracking, задача 3.4):
 * порядок FINE → BACKGROUND + экран про автозапуск/батарею (Xiaomi/Huawei).
 */
@Composable
fun OnboardingScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) step = 1 }
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = 2 }

    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            when (step) {
                0 -> {
                    Text("Шаг 1: точная геолокация")
                    Text("Нужна, чтобы открывать туман по вашим прогулкам")
                    Button(onClick = {
                        fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }) { Text("Разрешить гео") }
                }
                1 -> {
                    Text("Шаг 2: фоновая геолокация")
                    Text("Нужна, чтобы трек писался в фоне")
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else step = 2
                    }) { Text("Разрешить фон") }
                }
                else -> {
                    Text("Шаг 3: батарея и автозапуск")
                    Text("Отключите оптимизацию батареи и разрешите автозапуск (Xiaomi/Huawei), иначе система будет убивать трекинг")
                    Button(onClick = {
                        runCatching {
                            val pm = context.getSystemService(PowerManager::class.java)
                            if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                        scope.launch {
                            app.container.settingsRepository.setOnboardingDone()
                            runCatching { TrackingService.start(context) }
                            nav.navigate("map") { popUpTo("onboarding") { inclusive = true } }
                        }
                    }) { Text("Готово") }
                }
            }
        }
    }
}
