package ru.fogmap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.data.PrefsKeys
import ru.fogmap.map.FogLayer
import ru.fogmap.tracking.TrackingPreconditions
import ru.fogmap.tracking.TrackingService
import ru.fogmap.ui.BottomBar

/**
 * Карта — главный экран (spec app-shell/map-render).
 * Туман — полигонами MapKit ([FogLayer]), логотип/копирайты SDK не перекрываем
 * (полноэкранные оверлеи запрещены). Без Play Services — заглушка.
 */
@Composable
fun MapScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    val paused by app.container.dataStore.data
        .collectAsState(initial = androidx.datastore.preferences.core.preferencesOf()) 
    val isPaused = paused[PrefsKeys.PAUSED] ?: false
    val hasPlay = remember { TrackingPreconditions.playServicesAvailable(context) }

    Scaffold(bottomBar = { BottomBar(nav, "map") }) { pad ->
        if (!hasPlay) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Нет Play Services — трекинг недоступен")
                    Text("Карта и запись трека требуют Google Play Services")
                }
            }
            return@Scaffold
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Карта в тумане")
                    Text("Включите гео и погуляйте")
                    Button(onClick = { nav.navigate("onboarding") }) { Text("Выдать доступ") }
                }
            }
            return@Scaffold
        }
        // Авто-старт трекинга — НЕ зависит от карты: трекинг локален и обязан
        // работать без API-ключа (иначе без ключа не пишется ничего).
        DisposableEffect(isPaused) {
            val job = scope.launch {
                if (!isPaused && hasPlay && TrackingService.canTrack(context)) {
                    runCatching { TrackingService.start(context) }
                }
            }
            onDispose { job.cancel() }
        }
        // Без API-ключа MapView роняет процесс — показываем заглушку (задача 1.2:
        // ключ задается владельцем в local.properties вне git).
        if (!app.isMapKitReady) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Карта недоступна")
                    Text("Добавьте MAPKIT_API_KEY в local.properties")
                    Text("Трекинг и туман при этом работают локально")
                }
            }
            return@Scaffold
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val mapView = remember {
            MapView(context).apply {
                mapWindow.map.move(CameraPosition(Point(55.7558, 37.6173), 14f, 0f, 0f))
            }
        }
        DisposableEffect(lifecycle, mapView) {
            val fog = FogLayer(mapView, app.container.fogRepository, scope)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> { mapView.onStart(); fog.start() }
                    Lifecycle.Event.ON_STOP -> { fog.stop(); mapView.onStop() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                runCatching { fog.stop() }
            }
        }
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Button(
                onClick = {
                    scope.launch { app.container.settingsRepository.setPaused(!isPaused) }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) { Text(if (isPaused) "Продолжить" else "Пауза") }
        }
    }
}
