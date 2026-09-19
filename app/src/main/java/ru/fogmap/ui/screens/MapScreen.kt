package ru.fogmap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.layers.ObjectEvent
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.mapkit.user_location.UserLocationObjectListener
import com.yandex.mapkit.user_location.UserLocationView
import com.yandex.runtime.image.ImageProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import ru.fogmap.tracking.TrustEngine
import ru.fogmap.ui.BottomBar
import ru.fogmap.ui.theme.isDarkTheme

/**
 * Карта — главный экран (spec app-shell/map-render, fog-mask-canvas, location-cursor).
 * Туман — маской Canvas поверх MapView ([FogMaskOverlay]): глухая вуаль
 * первым кадром + мягкие дырки (fail-closed, рамок нет) + показная дырка
 * вокруг живого фикса (только пиксели, в БД не пишется). Клетки живут
 * в памяти (прелоад + Flow), на сдвиг камеры запросов в БД нет.
 * Курсор — нативный UserLocationLayer ПОД вуалью (красная точка, без стрелки
 * и круга точности, headingMode никогда не включается). Follow по умолчанию:
 * любой жест приостанавливает ведение на 10 сек тишины, FAB возвращает сразу.
 * Tilt зафиксирован в 0, rotate запрещен, север всегда сверху;
 * справа чип зума и FAB «Где я».
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
                // no-tilt-plus-north-up: жесты наклона и поворота запрещены
                // в источнике (вид строго сверху, север всегда сверху) —
                // иначе двухпальцевые свайпы уводили камеру в tilt ~50 /
                // azimuth != 0 и ломали проекцию дырок (схлопывание диагонали).
                mapWindow.map.isTiltGesturesEnabled = false
                mapWindow.map.isRotateGesturesEnabled = false
                mapWindow.map.move(CameraPosition(Point(55.7558, 37.6173), 14f, 0f, 0f))
                // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                runCatching { DevCameraStats.onMove() }
            }
        }
        // Курсор (location-cursor 2.1): нативный слой ПОД вуалью. Сильные ссылки
        // обязательны — SDK держит слушателей как WeakReference (как camListenerRef).
        // headingMode никогда не включаем: он крутит карту и ломает север-сверху.
        val userLocationLayer: UserLocationLayer? = remember(mapView) {
            runCatching {
                MapKitFactory.getInstance().createUserLocationLayer(mapView.mapWindow)
            }.getOrNull()
        }
        // Живая позиция из слоя (показ vs архив: трек правды — в БД, слой — экран).
        var liveLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var liveView by remember { mutableStateOf<UserLocationView?>(null) }
        var liveLastMs by remember { mutableStateOf(0L) }
        var nowTickMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        // Follow (location-cursor 4.1): ведем по умолчанию, жест ставит на паузу.
        var followActive by remember { mutableStateOf(true) }
        var resumeJob by remember { mutableStateOf<Job?>(null) }
        var firstLiveFix by remember { mutableStateOf(false) }
        fun pullLivePosition(): Pair<Double, Double>? =
            runCatching { userLocationLayer?.cameraPosition()?.target }
                .getOrNull()?.let { it.latitude to it.longitude }
        // Растр, не вектор: вектор MapKit не отрисовывает и молча оставляет
        // свой дефолтный значок. Масштаб 1.5 от базы 12dp: красное ядро ~18dp
        // на экране. Провайдер и стиль кешируем — применяются на каждое
        // обновление объекта, иначе слой возвращает свой дефолт.
        val pinProvider = remember(context) {
            runCatching { ImageProvider.fromResource(context, R.drawable.ic_my_location) }
                .getOrNull()
        }
        val pinStyle = remember {
            IconStyle().setAnchor(PointF(0.5f, 0.5f)).setScale(1.5f)
        }
        fun styleLocationView(view: UserLocationView) {
            val provider = pinProvider ?: return
            runCatching { view.pin.setIcon(provider, pinStyle) }
            // Стрелки и круга точности нет по спеку: только красный кружок.
            runCatching { view.arrow.isVisible = false }
            runCatching { view.accuracyCircle.isVisible = false }
        }
        val locationListener: UserLocationObjectListener = remember(context, userLocationLayer) {
            object : UserLocationObjectListener {
                override fun onObjectAdded(view: UserLocationView) {
                    styleLocationView(view)
                    liveView = view
                    pullLivePosition()?.let {
                        liveLatLon = it
                        liveLastMs = SystemClock.elapsedRealtime()
                    }
                    // ВРЕМЕННОЕ (dev-logging): редкое событие, не кадр.
                    DevLog.d("UI", "user_location", mapOf("event" to "added"))
                }

                override fun onObjectRemoved(view: UserLocationView) {
                    if (liveView === view) liveView = null
                    liveLatLon = null
                    // ВРЕМЕННОЕ (dev-logging): редкое событие, не кадр.
                    DevLog.d("UI", "user_location", mapOf("event" to "removed"))
                }

                override fun onObjectUpdated(view: UserLocationView, event: ObjectEvent) {
                    liveView = view
                    // Стиль на каждое обновление: слой может пересоздавать виды
                    // и возвращать свой дефолт (зелень) вместо нашей иконки.
                    styleLocationView(view)
                    pullLivePosition()?.let {
                        liveLatLon = it
                        liveLastMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        val locationListenerRef = remember(locationListener) {
            java.lang.ref.WeakReference(locationListener)
        }
        // Состояние камеры для маски и чипа зума (remove-map-rotation:
        // север всегда сверху, azimuth нет — только зум и target).
        // Target — парой (у Point нет equals, remember бы пересчитывал всегда).
        var camZoom by remember { mutableStateOf(14f) }
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
        // Камера на текущую геолокацию через Fused: стартовый прыжок (чтобы не
        // открываться в Москве) и fallback FAB пока слой не отдал позицию.
        // Всё через runCatching: microG может вернуть null.
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
        // Камера к живой точке слоя (follow/FAB): зум caller задает сам.
        // Вызывать только с UI-потока, tilt всегда 0. Fallback на Fused здесь
        // нет — его держит moveToMyLocation для старта и пустого слоя.
        fun moveToLive(zoom: Float) {
            val target = pullLivePosition() ?: liveLatLon ?: return
            runCatching {
                mapView.mapWindow.map.move(
                    CameraPosition(Point(target.first, target.second), zoom, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.8f), null
                )
                // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                runCatching { DevCameraStats.onMove() }
            }
        }
        // Слушатель камеры: зум/азимут/target для маски и UI.
        // Tilt-жест отключен в SDK (см. создание mapView выше), поэтому
        // tilt-возвратов нет (no-tilt-plus-diag). Единственный исходящий move —
        // follow-возврат через 10 сек тишины с причиной APPLICATION: он петлю
        // не образует (жестовая ветка срабатывает только на GESTURES).
        // MapKit 4.42.0 принимает слушателя как WeakReference (как раньше FogLayer).
        val camListener = remember {
            CameraListener { _, pos, reason, _ ->
                camZoom = pos.zoom
                camTarget = pos.target.latitude to pos.target.longitude
                // Follow (location-cursor 4.1): жест пользователя ставит ведение
                // на паузу на 10 сек тишины. Свои move() идут с APPLICATION
                // и таймер не трогают — иначе была бы самоподдерживающаяся петля.
                if (reason == CameraUpdateReason.GESTURES && liveLatLon != null) {
                    followActive = false
                    resumeJob?.cancel()
                    resumeJob = scope.launch {
                        delay(10_000)
                        followActive = true
                        moveToLive(camZoom)
                    }
                }
                // ВРЕМЕННОЕ (dev-logging): только примитивы, ноль строк в колбэке.
                // Углов камеры в логе нет (север сверху, наклон 0 по построению).
                // Синхронных move() в колбэке нет: follow-возврат идет только
                // асинхронно через таймер с причиной APPLICATION.
                runCatching {
                    DevCameraStats.onEvent(
                        pos.zoom,
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
        // Видимость слоя (location-cursor 2.1–2.2): жив пока открыта карта,
        // пауза записи его не касается. headingMode не трогаем никогда.
        DisposableEffect(mapView, userLocationLayer) {
            runCatching { userLocationLayer?.setObjectListener(locationListenerRef) }
            runCatching { userLocationLayer?.setHeadingModeActive(false) }
            runCatching { userLocationLayer?.setVisible(true) }
            onDispose {
                runCatching { userLocationLayer?.setVisible(false) }
                resumeJob?.cancel()
            }
        }
        // Ведение за живой точкой (location-cursor 4.1): первый fix прыгает на
        // 15f как раньше (стартовый moveToMyLocation уже отработал рядом),
        // дальше едем на зуме пользователя. В ручном осмотре стоим.
        LaunchedEffect(liveLatLon) {
            val fix = liveLatLon ?: return@LaunchedEffect
            if (!followActive) return@LaunchedEffect
            if (!firstLiveFix) {
                firstLiveFix = true
                moveToLive(15f)
            } else {
                moveToLive(camZoom)
            }
        }
        // Stale-тикер (location-cursor 2.2): тишина дольше порога TrustEngine —
        // точку приглушаем, пятно и ведение остаются. Тик редкий, не кадр.
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                nowTickMs = SystemClock.elapsedRealtime()
            }
        }
        val isLiveStale = liveLatLon != null && liveLastMs > 0L &&
            (nowTickMs - liveLastMs) > TrustEngine.SILENCE_RESET_S * 1000L
        LaunchedEffect(liveView, isLiveStale) {
            val view = liveView ?: return@LaunchedEffect
            runCatching { view.pin.opacity = if (isLiveStale) 0.5f else 1f }
        }
        // Дырки в пикселях: из памяти, проекция worldToScreen в UI-потоке.
        // Ключи — клетки + зум + target: иначе при пане одним пальцем
        // (зум тот же) дырки стоят, а карта едет под ними.
        // Север всегда сверху (remove-map-rotation): проекция по двум углам
        // точна, azimuth-ключа нет.
        // worldToScreen может вернуть null (точка за камерой) — тогда дырки
        // нет (fail-closed). Лог — след спайка 1.1–1.2.
        val holesPx: List<HolePx> = remember(cells, camZoom, camTarget, liveLatLon) {
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
            // Показная дырка (location-cursor 3.2): только пиксели вокруг живого
            // фикса, в БД не пишется. Добавляется даже при overBudget (+1 дырка
            // дешева, а якорь «где я» нужен именно в плотной застройке).
            // Правила зумов — внутри liveHoles (ниже 11 — пусто, видна точка).
            val live = liveLatLon
            if (live != null) {
                val win = mapView.mapWindow
                for (h in FogMask.liveHoles(live.first, live.second, camZoom)) {
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
            // Правая колонка: зум + «Где я» (remove-map-rotation: компаса нет,
            // север всегда сверху).
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                    // Follow (location-cursor 4.1): возврат сразу без таймера.
                    // Точка слоя первична, Fused — fallback пока слоя нет.
                    resumeJob?.cancel()
                    followActive = true
                    if (liveLatLon != null) moveToLive(camZoom)
                    else moveToMyLocation(16f)
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
