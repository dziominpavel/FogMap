package ru.fogmap.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.tracking.TrackingService

/**
 * Онбординг разрешений (ui-dark-redesign 3.4):
 * степпер 1-2-3 с прогрессом и иллюстрацией каждого шага,
 * порядок FINE → BACKGROUND → батарея.
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

    val titles = listOf("Точная геолокация", "Фоновая геолокация", "Батарея и автозапуск")
    val bodies = listOf(
        "Нужна, чтобы открывать туман по вашим прогулкам",
        "Нужна, чтобы трек писался в фоне",
        "Отключите оптимизацию батареи и разрешите автозапуск (Xiaomi/Huawei), иначе система будет убивать трекинг"
    )
    val illustrations = listOf(
        R.drawable.img_onboarding_geo,
        R.drawable.img_onboarding_bg,
        R.drawable.img_onboarding_battery
    )

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Шаг ${step + 1} из 3: ${titles[step]}",
                style = MaterialTheme.typography.titleLarge
            )
            LinearProgressIndicator(
                progress = { (step + 1) / 3f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Image(
                painterResource(illustrations[step]),
                contentDescription = null,
                modifier = Modifier.size(220.dp)
            )
            Text(bodies[step], style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            when (step) {
                0 -> {
                    Button(onClick = {
                        fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }) { Text("Разрешить гео") }
                }
                1 -> {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else step = 2
                    }) { Text("Разрешить фон") }
                }
                else -> {
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
