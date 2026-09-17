package ru.fogmap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.yandex.mapkit.Animation
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.ThemeModes
import ru.fogmap.map.FogLayer
import ru.fogmap.tracking.TrackingPreconditions
import ru.fogmap.tracking.TrackingService
import ru.fogmap.ui.BottomBar
import ru.fogmap.ui.theme.isDarkTheme

/**
 * Карта — главный экран (spec app-shell/map-render).
 * Туман — полигонами MapKit ([FogLayer]), логотип/копирайты SDK не перекрываем
 * (полноэкранные оверлеи запрещены). Без Play Services — заглушка.
 * Управление одной рукой: FAB-стек справа внизу + статус-чип сессии слева
 * (ui-dark-redesign 2.2, отступ снизу — не перекрывать логотип Яндекса).
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
    // Ночная карта синхронно с темой оболочки (4.2): темная → night,
    // светлая → day, system → за системой. Fallback — FogLayer.FORCE_DAY_MAP.
    val darkTheme = isDarkTheme(
        paused[PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT,
        isSystemInDarkTheme()
    )
    val useNightMap = darkTheme && !FogLayer.FORCE_DAY_MAP
    val fogFill = if (darkTheme) FogLayer.FOG_FILL_DARK else FogLayer.FOG_FILL_LIGHT
    val fogBorder = if (darkTheme) FogLayer.FOG_BORDER_DARK else FogLayer.FOG_BORDER_LIGHT

    Scaffold(bottomBar = { BottomBar(nav, "map") }) { pad ->
        if (!hasPlay) {
            MapPlaceholder(
                modifier = Modifier.fillMaxSize().padding(pad),
                title = "Нет Play Services",
                body = "Карта и запись трека требуют Google Play Services",
                illustration = null,
                ctaLabel = null,
                onCta = {}
            )
            return@Scaffold
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            MapPlaceholder(
                modifier = Modifier.fillMaxSize().padding(pad),
                title = "Карта в тумане",
                body = "Включите гео и погуляйте — туман начнет открываться",
                illustration = R.drawable.img_empty_map,
                ctaLabel = "Выдать доступ",
                onCta = { nav.navigate("onboarding") }
            )
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
            MapPlaceholder(
                modifier = Modifier.fillMaxSize().padding(pad),
                title = "Карта недоступна",
                body = "Добавьте MAPKIT_API_KEY в local.properties. Трекинг и туман при этом работают локально",
                illustration = null,
                ctaLabel = null,
                onCta = {}
            )
            return@Scaffold
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val mapView = remember {
            MapView(context).apply {
                mapWindow.map.move(CameraPosition(Point(55.7558, 37.6173), 14f, 0f, 0f))
            }
        }
        // Камера на текущую геолокацию: стартовая (чтобы не открываться в Москве)
        // и кнопка «Где я». Всё через runCatching: microG может вернуть null.
        // Вызывать только с UI-потока (MapKit роняет процесс из фона).
        fun moveToMyLocation(zoom: Float) {
            runCatching {
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        runCatching {
                            mapView.mapWindow.map.move(
                                CameraPosition(Point(loc.latitude, loc.longitude), zoom, 0f, 0f),
                                Animation(Animation.Type.SMOOTH, 0.8f), null
                            )
                        }
                    } else {
                        runCatching {
                            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener { fresh ->
                                    if (fresh != null) {
                                        runCatching {
                                            mapView.mapWindow.map.move(
                                                CameraPosition(
                                                    Point(fresh.latitude, fresh.longitude),
                                                    zoom, 0f, 0f
                                                ),
                                                Animation(Animation.Type.SMOOTH, 0.8f), null
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        DisposableEffect(lifecycle, mapView, useNightMap, fogFill, fogBorder) {
            // Ночной режим — только при смене темы, не при каждой рекомпозиции.
            runCatching { mapView.mapWindow.map.setNightModeEnabled(useNightMap) }
            val fog = FogLayer(mapView, app.container.fogRepository, scope, fogFill, fogBorder)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> { mapView.onStart(); fog.start() }
                    Lifecycle.Event.ON_STOP -> { fog.stop(); mapView.onStop() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            // Сразу к своей точке вместо Москвы (если локация известна).
            moveToMyLocation(15f)
            onDispose {
                lifecycle.removeObserver(observer)
                runCatching { fog.stop() }
            }
        }
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // Статус-чип сессии: read-only, слева внизу над логотипом SDK.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 64.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isPaused) "⏸ Пауза" else "● Запись идёт",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isPaused) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            // FAB-стек: пауза + «Где я», одна рука.
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch { app.container.settingsRepository.setPaused(!isPaused) }
                    }
                ) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Продолжить запись" else "Пауза"
                    )
                }
                FloatingActionButton(onClick = { moveToMyLocation(16f) }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Где я")
                }
            }
        }
    }
}

/**
 * Заглушка карты карточкой с CTA (ui-dark-redesign 2.3): нет Play Services /
 * нет гео / нет ключа. Карту не рисуем, атрибуцию не перекрываем.
 */
@Composable
private fun MapPlaceholder(
    modifier: Modifier,
    title: String,
    body: String,
    illustration: Int?,
    ctaLabel: String?,
    onCta: () -> Unit
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (illustration != null) {
                    Image(
                        painterResource(illustration),
                        contentDescription = null,
                        modifier = Modifier.size(160.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (ctaLabel != null) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCta) { Text(ctaLabel) }
                }
            }
        }
    }
}
