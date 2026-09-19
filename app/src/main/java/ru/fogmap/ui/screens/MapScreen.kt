package ru.fogmap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.yandex.mapkit.Animation
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.ThemeModes
import ru.fogmap.diag.DevCameraStats
import ru.fogmap.diag.DevLog
import ru.fogmap.diag.DevPerfMonitor
import ru.fogmap.diag.DevRenderStats
import ru.fogmap.fog.FogGrid.Cell
import ru.fogmap.map.FogMask
import ru.fogmap.map.FogMask.HolePx
import ru.fogmap.map.FogMaskOverlay
import ru.fogmap.tracking.TrackingPreconditions
import ru.fogmap.tracking.TrackingService
import ru.fogmap.ui.BottomBar
import ru.fogmap.ui.theme.isDarkTheme
import kotlin.math.abs

/**
 * Карта — главный экран (spec app-shell/map-render, fog-mask-canvas).
 * Туман — маской Canvas поверх MapView ([FogMaskOverlay]): глухая вуаль
 * первым кадром + мягкие дырки (fail-closed, рамок нет). Клетки живут
 * в памяти (прелоад + Flow), на сдвиг камеры запросов в БД нет.
 * Tilt зафиксирован в 0, чип зума и компас — справа над FAB.
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
    // Ночная карта синхронно с темой оболочки: темная → night,
    // светлая → day, system → за системой. Fallback — FogMask.FORCE_DAY_MAP.
    val darkTheme = isDarkTheme(
        paused[PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT,
        isSystemInDarkTheme()
    )
    val useNightMap = darkTheme && !FogMask.FORCE_DAY_MAP
    val veilColor = Color(if (darkTheme) FogMask.VEIL_DARK else FogMask.VEIL_LIGHT)

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
                // no-tilt-plus-diag: жест наклона запрещен в источнике (вид строго
                // сверху, tilt всегда 0) — иначе двухпальцевый свайп уводит камеру
                // в tilt ~50 и запускает петлю корректирующих move().
                mapWindow.map.isTiltGesturesEnabled = false
                mapWindow.map.move(CameraPosition(Point(55.7558, 37.6173), 14f, 0f, 0f))
                // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                runCatching { DevCameraStats.onMove() }
            }
        }
        // Состояние камеры для маски/чипа/компаса (fog-mask-canvas 4.2–4.3).
        // Target — парой (у Point нет equals, remember бы пересчитывал всегда).
        var camZoom by remember { mutableStateOf(14f) }
        var camAzimuth by remember { mutableStateOf(0f) }
        var camTarget by remember { mutableStateOf(55.7558 to 37.6173) }
        // Туман в памяти: прелоад + живые инкременты (2.1–2.2).
        var cells by remember { mutableStateOf(emptySet<Cell>()) }
        LaunchedEffect(mapView) {
            launch {
                val all = runCatching { app.container.fogRepository.allCells() }
                    .getOrDefault(emptySet())
                cells = all
                val count = runCatching { app.container.fogRepository.cellCount() }
                    .getOrDefault(-1L)
                // ВРЕМЕННОЕ (dev-logging): прелоад — редкое событие, не кадр.
                DevLog.i("RENDER", "preload", mapOf("cells" to all.size, "db_count" to count))
            }
            launch {
                runCatching {
                    app.container.fogRepository.observeCells().collect { cells = it }
                }
            }
        }
        // ВРЕМЕННОЕ (dev-logging): число клеток для PERF-агрегата.
        LaunchedEffect(cells.size) { DevPerfMonitor.setCells(cells.size) }
        // Камера на текущую геолокацию: стартовая (чтобы не открываться в Москве)
        // и кнопка «Где я». Всё через runCatching: microG может вернуть null.
        // Вызывать только с UI-потока (MapKit роняет процесс из фона).
        // Tilt всегда 0 (fog-mask-canvas 4.1): вид строго сверху.
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
                            // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                            runCatching { DevCameraStats.onMove() }
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
                                            // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                                            runCatching { DevCameraStats.onMove() }
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        // Слушатель камеры: зум/азимут/target для маски и UI.
        // Tilt-жест отключен в SDK (см. создание mapView выше), поэтому
        // корректирующего возврата нет: слушатель никогда не запускает
        // move() — это и была самоподдерживающаяся петля (no-tilt-plus-diag).
        // MapKit 4.42.0 принимает слушателя как WeakReference (как раньше FogLayer).
        val camListener = remember {
            CameraListener { _, pos, _, _ ->
                camZoom = pos.zoom
                camAzimuth = pos.azimuth
                camTarget = pos.target.latitude to pos.target.longitude
                // ВРЕМЕННОЕ (dev-logging): только примитивы, ноль строк в колбэке.
                // tiltFixed=false: корректирующих движений больше нет.
                runCatching {
                    DevCameraStats.onEvent(
                        pos.zoom, pos.azimuth, pos.tilt, false,
                        SystemClock.elapsedRealtime()
                    )
                }
            }
        }
        val camListenerRef = remember(camListener) {
            java.lang.ref.WeakReference(camListener)
        }
        DisposableEffect(lifecycle, mapView, useNightMap) {
            // Ночной режим — только при смене темы, не при каждой рекомпозиции.
            runCatching { mapView.mapWindow.map.setNightModeEnabled(useNightMap) }
            runCatching { mapView.mapWindow.map.addCameraListener(camListenerRef) }
            // ВРЕМЕННОЕ (dev-logging): жизненный цикл + PERF-монитор.
            DevLog.d("UI", "map_lifecycle", mapOf("event" to "ON_START"))
            DevPerfMonitor.start(context)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        mapView.onStart()
                        DevLog.d("UI", "mapview", mapOf("event" to "ON_START"))
                    }
                    Lifecycle.Event.ON_STOP -> {
                        mapView.onStop()
                        DevLog.d("UI", "mapview", mapOf("event" to "ON_STOP"))
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            // Сразу к своей точке вместо Москвы (если локация известна).
            moveToMyLocation(15f)
            onDispose {
                lifecycle.removeObserver(observer)
                DevLog.d("UI", "map_lifecycle", mapOf("event" to "ON_STOP"))
                DevPerfMonitor.stop()
                runCatching { mapView.mapWindow.map.removeCameraListener(camListenerRef) }
            }
        }
        // Дырки в пикселях: из памяти, проекция worldToScreen в UI-потоке.
        // Ключи — клетки + зум + азимут + target: иначе при пане одним пальцем
        // (зум тот же) дырки стоят, а карта едет под ними.
        // worldToScreen может вернуть null (точка за камерой) — тогда дырки
        // нет (fail-closed). Лог — след спайка 1.1–1.2.
        val holesPx: List<HolePx> = remember(cells, camZoom, camAzimuth, camTarget) {
            // ВРЕМЕННОЕ (dev-logging): замеры merge vs проекция, агрегат вместо спама.
            val t0 = System.nanoTime()
            val region = runCatching {
                val r = mapView.mapWindow.map.visibleRegion
                val topLat = maxOf(r.topLeft.latitude, r.topRight.latitude)
                val bottomLat = minOf(r.bottomLeft.latitude, r.bottomRight.latitude)
                val leftLon = minOf(r.topLeft.longitude, r.bottomLeft.longitude)
                val rightLon = maxOf(r.topRight.longitude, r.bottomRight.longitude)
                val dLat = (topLat - bottomLat) * 0.25
                val dLon = (rightLon - leftLon) * 0.25
                FogMask.RegionBox(topLat + dLat, bottomLat - dLat, leftLon - dLon, rightLon + dLon)
            }.getOrNull()
            val holes = FogMask.holesForZoom(cells, camZoom, region)
            val tMerge = System.nanoTime()
            val out = ArrayList<HolePx>(holes.size.coerceAtMost(FogMask.MAX_HOLES))
            var nullProj = 0
            val isOverBudget = FogMask.overBudget(holes)
            if (!isOverBudget) {
                val win = mapView.mapWindow
                for (h in holes) {
                    val (tl, br) = FogMask.holeBounds(h)
                    val s1 = runCatching { win.worldToScreen(Point(tl.first, tl.second)) }.getOrNull()
                    val s2 = runCatching { win.worldToScreen(Point(br.first, br.second)) }.getOrNull()
                    if (s1 == null || s2 == null) {
                        nullProj++
                        continue
                    }
                    out.add(
                        FogMask.ensureMinPx(
                            HolePx(
                                minOf(s1.x, s2.x), minOf(s1.y, s2.y),
                                maxOf(s1.x, s2.x), maxOf(s1.y, s2.y)
                            )
                        )
                    )
                }
            }
            // ВРЕМЕННОЕ (dev-logging): агрегат 2 сек + W при кадре > 500мс/overBudget.
            // Покадровый Log.d удален (2.3): при пане был шторм строк с format().
            val t1 = System.nanoTime()
            runCatching {
                DevRenderStats.onFrame(
                    dtMergeMs = (tMerge - t0) / 1e6,
                    dtProjMs = (t1 - tMerge) / 1e6,
                    dtTotalMs = (t1 - t0) / 1e6,
                    cells = cells.size,
                    holes = out.size,
                    nullProj = nullProj,
                    overBudget = isOverBudget,
                    nowMono = SystemClock.elapsedRealtime()
                )
            }
            out
        }
        // Компас виден при отклонении от севера > 10° (azimuth 0..360).
        val northOff = minOf(camAzimuth, 360f - camAzimuth).let { abs(it) }
        val showCompass = northOff > 10f
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // Маска поверх карты (личная сборка: MAY перекрывать логотип).
            FogMaskOverlay(
                holes = holesPx,
                veilColor = veilColor,
                modifier = Modifier.fillMaxSize()
            )
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
            // Правая колонка: компас + зум + «Где я» (4.2–4.3).
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showCompass) {
                    FloatingActionButton(
                        onClick = {
                            // ВРЕМЕННОЕ (dev-logging): нажатие компаса.
                            DevLog.d("UI", "tap", mapOf("target" to "compass_reset"))
                            runCatching {
                                val pos = mapView.mapWindow.map.cameraPosition
                                mapView.mapWindow.map.move(
                                    CameraPosition(pos.target, pos.zoom, 0f, 0f),
                                    Animation(Animation.Type.SMOOTH, 0.5f), null
                                )
                                // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                                runCatching { DevCameraStats.onMove() }
                            }
                        }
                    ) {
                        // Стрелка-компас в стиле Яндекс Карт: красная северная
                        // половина, серая южная, поворот за картой.
                        Canvas(Modifier.size(24.dp)) {
                            rotate(-camAzimuth) {
                                val c = center
                                val r = size.minDimension / 2f
                                val w = r * 0.38f
                                drawPath(
                                    Path().apply {
                                        moveTo(c.x, c.y - r)
                                        lineTo(c.x + w, c.y)
                                        lineTo(c.x - w, c.y)
                                        close()
                                    },
                                    Color(0xFFE53935)
                                )
                                drawPath(
                                    Path().apply {
                                        moveTo(c.x, c.y + r)
                                        lineTo(c.x + w, c.y)
                                        lineTo(c.x - w, c.y)
                                        close()
                                    },
                                    Color(0xFFB0BEC5)
                                )
                                drawCircle(Color.White, radius = r * 0.14f, center = c)
                            }
                        }
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    Text(
                        "%.1f".format(camZoom),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                FloatingActionButton(onClick = {
                    // ВРЕМЕННОЕ (dev-logging): нажатие «Где я».
                    DevLog.d("UI", "tap", mapOf("target" to "my_location"))
                    moveToMyLocation(16f)
                }) {
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
