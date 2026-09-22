package ru.fogmap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.Location
import android.location.LocationManager
import android.os.Looper
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
import com.yandex.mapkit.user_location.UserLocationIconChanged
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.mapkit.user_location.UserLocationObjectListener
import com.yandex.mapkit.user_location.UserLocationView
import com.yandex.runtime.image.ImageProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
        // Живая позиция (first-launch-visibility): единый экранный источник —
        // позиция слоя MapKit приоритетна, fallback Fused заполняет только
        // молчание слоя. Производная liveLatLon = слой ?: fallback — одна точка
        // правды для показной дырки, follow, FAB и стартового прыжка.
        var layerLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var fusedLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var layerLastMs by remember { mutableStateOf(0L) }
        var fusedLastMs by remember { mutableStateOf(0L) }
        var liveView by remember { mutableStateOf<UserLocationView?>(null) }
        var nowTickMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        // Производные: позиция — слой иначе fallback; свежесть — от любого
        // источника (чип и stale-тикер не различают, откуда пришёл фикс).
        val liveLatLon = layerLatLon ?: fusedLatLon
        val liveLastMs = maxOf(layerLastMs, fusedLastMs)
        // (спека map-render) Мастер геолокации вне экрана: обновляется по тику
        // 30 с и при ON_START — смена состояния чипа без перезагрузки экрана.
        fun locationEnabled(): Boolean = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm != null && (
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                )
        }.getOrDefault(true)
        var geoEnabled by remember { mutableStateOf(locationEnabled()) }
        // Дедуп лога screen_pos: гейт работает на каждом фиксе, логируем
        // переходы accepted↔rejected, не каждый фикс (dev-logging против шторма).
        var fusedLogState by remember { mutableStateOf("") }
        // Дедуп лога чтения позиции слоя: переходы ok↔fail, не каждый тик.
        var layerPullLogState by remember { mutableStateOf("") }
        // Единая обработка фикса Fused (спека map-render): гейт 100 м, приоритет
        // слоя, лог screen_pos (source=fused, via — путь доставки).
        fun onFusedFix(loc: Location, via: String) {
            val acc = if (loc.hasAccuracy()) loc.accuracy else null
            val plausible = GeoStatus.plausible(acc)
            // Свежесть — от любого источника (design: «фикс свежее 120 с»):
            // до приоритета слоя, иначе fusedLastMs замерзал при активном
            // слое и чип ложно уходил в «Протухло» при живом GPS (поле).
            if (plausible) fusedLastMs = SystemClock.elapsedRealtime()
            if (layerLatLon != null) return // позицию ведёт слой — fallback молчит
            if (plausible) {
                fusedLatLon = loc.latitude to loc.longitude
                if (fusedLogState != "accepted") {
                    fusedLogState = "accepted"
                    DevLog.i(
                        "UI", "screen_pos",
                        mapOf(
                            "source" to "fused", "via" to via,
                            "decision" to "accepted", "acc" to (acc ?: -1f)
                        )
                    )
                }
            } else if (fusedLogState != "rejected") {
                fusedLogState = "rejected"
                DevLog.i(
                    "UI", "screen_pos",
                    mapOf(
                        "source" to "fused", "via" to via,
                        "decision" to "rejected", "acc" to (acc ?: -1f)
                    )
                )
            }
        }
        // Follow (location-cursor 4.1): ведем по умолчанию, жест ставит на паузу.
        var followActive by remember { mutableStateOf(true) }
        var resumeJob by remember { mutableStateOf<Job?>(null) }
        var firstLiveFix by remember { mutableStateOf(false) }
        fun pullLivePosition(): Pair<Double, Double>? =
            runCatching { userLocationLayer?.cameraPosition()?.target }
                .getOrNull()?.let { it.latitude to it.longitude }
        // Синк снимка живой позиции слоя (полевой фикс): события объекта
        // приходят не на каждый GPS-фикс — после старта layerLatLon замерзал,
        // показная дырка и follow стояли, а FAB читал живое чтение и камера
        // разъезжалась с крестом (спека: курсор и дырка двигаются в реальном
        // времени вслед за фиксом). Чтение живое; запись — по эпсилону, чтобы
        // дрожание ±1 м не будило рекомпозицию и follow каждый тик.
        fun syncLayerPosition(force: Boolean = false): Pair<Double, Double>? {
            val p = pullLivePosition()
            if (p == null) {
                if (layerPullLogState != "fail") {
                    layerPullLogState = "fail"
                    DevLog.w(
                        "UI", "screen_pos",
                        mapOf("source" to "layer", "decision" to "pull_failed")
                    )
                }
                return null
            }
            if (layerPullLogState != "ok") {
                // Первый удачный pull и восстановление после провала —
                // читаемость: тик молча чинит снимок, без лога перехода
                // непонятно, живёт fallback или позиция слоя.
                layerPullLogState = "ok"
                DevLog.i(
                    "UI", "screen_pos",
                    mapOf("source" to "layer", "decision" to "accepted")
                )
            }
            if (liveView == null) return p // объекта нет — снимок не пишем
            val cur = layerLatLon
            if (force || cur == null || distMeters(cur, p) > LIVE_SYNC_EPS_M) {
                layerLatLon = p
                layerLastMs = SystemClock.elapsedRealtime()
            }
            return p
        }
        // Растр, не вектор: вектор MapKit не отрисовывает и молча оставляет
        // свой дефолтный значок. Масштаб 1.5 от базы 12dp: красное ядро ~18dp
        // на экране. Провайдер и стиль кешируем — применяются на каждое
        // обновление объекта, иначе слой возвращает свой дефолт.
        // (always-red-cursor): тем же растром красим ОБЕ плейсмарки слоя —
        // SDK сам переключает PIN (покой) / ARROW (движение), прятать стрелку
        // бесполезно: переход в ARROW возвращает ей видимость и выводит
        // желтый дефолт. Круг симметричен, вращение иконки курсом нейтрально.
        val pinProvider = remember(context) {
            runCatching { ImageProvider.fromResource(context, R.drawable.ic_my_location) }
                .getOrNull()
        }
        val pinStyle = remember {
            IconStyle().setAnchor(PointF(0.5f, 0.5f)).setScale(1.5f)
        }
        fun styleLocationView(view: UserLocationView) {
            // ВРЕМЕННОЕ (dev-logging): явные неуспехи вместо молчаливого catch-all.
            if (runCatching { view.isValid() }.getOrDefault(false).not()) {
                DevLog.w("UI", "user_location", mapOf("event" to "style_skipped", "reason" to "invalid_view"))
                return
            }
            val provider = pinProvider
            if (provider == null) {
                DevLog.w("UI", "user_location", mapOf("event" to "style_failed", "part" to "provider_null"))
            } else {
                runCatching {
                    view.pin.setIcon(provider, pinStyle)
                    view.pin.isVisible = true
                }.onFailure {
                    DevLog.w("UI", "user_location", mapOf("event" to "style_failed", "part" to "pin"))
                }
                runCatching {
                    view.arrow.setIcon(provider, pinStyle)
                    view.arrow.isVisible = true
                }.onFailure {
                    DevLog.w("UI", "user_location", mapOf("event" to "style_failed", "part" to "arrow"))
                }
            }
            // Круг точности скрыт всегда, независимо от провайдера иконок.
            runCatching { view.accuracyCircle.isVisible = false }
                .onFailure {
                    DevLog.w("UI", "user_location", mapOf("event" to "style_failed", "part" to "accuracy"))
                }
        }
        val locationListener: UserLocationObjectListener = remember(context, userLocationLayer) {
            object : UserLocationObjectListener {
                override fun onObjectAdded(view: UserLocationView) {
                    styleLocationView(view)
                    liveView = view
                    // ВРЕМЕННОЕ (dev-logging): редкое событие, не кадр.
                    DevLog.d("UI", "user_location", mapOf("event" to "added"))
                    // (first-launch-visibility): слой отдал объект — он
                    // приоритетный экранный источник, fallback-точка гаснет.
                    // screen_pos (accepted/pull_failed) логирует сам синк по
                    // переходу состояния чтения: событие и тик не дублируют.
                    syncLayerPosition(force = true)
                }

                override fun onObjectRemoved(view: UserLocationView) {
                    if (liveView === view) liveView = null
                    // Слой сдал объект: своя позиция очищается, fallback-
                    // источник живёт последним принятым фиксом (спека
                    // map-render: на экране всегда одна точка).
                    layerLatLon = null
                    layerLastMs = 0L
                    layerPullLogState = "" // следующий add снова логирует accepted
                    // ВРЕМЕННОЕ (dev-logging): редкое событие, не кадр.
                    DevLog.d("UI", "user_location", mapOf("event" to "removed"))
                    DevLog.i("UI", "screen_pos", mapOf("source" to "layer", "decision" to "removed"))
                }

                override fun onObjectUpdated(view: UserLocationView, event: ObjectEvent) {
                    liveView = view
                    // Стиль на каждое обновление: слой может пересоздавать виды
                    // и возвращать свой дефолт вместо нашей иконки. Тип иконки
                    // логируем только на переходах PIN/ARROW, иначе шторм строк.
                    val iconType = (event as? UserLocationIconChanged)?.iconType?.name
                    styleLocationView(view)
                    if (iconType != null) {
                        DevLog.d("UI", "user_location", mapOf("event" to "icon_changed", "icon" to iconType))
                    }
                    syncLayerPosition(force = true)
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
        // Гистерезис показа (smooth-fog-zoom): раз упав в пятна присутствия,
        // держим их пока точных дырок больше RETURN_THRESHOLD. Сбрасывается
        // сам при уходе зума из зоны >= DETAIL_ZOOM (см. wantPresence ниже).
        var presenceStuck by remember { mutableStateOf(false) }
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
        // открываться в Москве) и fallback FAB пока позиции нет. Оба пути — через
        // гейт правдоподобия (спека map-render): фикс хуже 100 м камеру не
        // двигает, фантомный сетевой фикс не увозит взгляд в «Москва-центр».
        // Всё через runCatching: microG может вернуть null.
        // Вызывать только с UI-потока (MapKit роняет процесс из фона).
        // Tilt всегда 0 (fog-mask-canvas 4.1): вид строго сверху.
        fun moveToMyLocation(zoom: Float) {
            fun jump(loc: Location?, via: String) {
                if (loc == null) {
                    DevLog.i(
                        "UI", "screen_pos",
                        mapOf("source" to "fused", "via" to via, "decision" to "no_fix")
                    )
                    return
                }
                val acc = if (loc.hasAccuracy()) loc.accuracy else null
                val plausible = GeoStatus.plausible(acc)
                onFusedFix(loc, via) // состояние fallback + лог accepted/rejected
                if (!plausible) return // гейт не прошёл — камера стоит на месте
                runCatching {
                    mapView.mapWindow.map.move(
                        CameraPosition(Point(loc.latitude, loc.longitude), zoom, 0f, 0f),
                        Animation(Animation.Type.SMOOTH, 0.8f), null
                    )
                    // ВРЕМЕННОЕ (dev-logging): исходящее программное движение.
                    runCatching { DevCameraStats.onMove() }
                }
            }
            runCatching {
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.lastLocation.addOnSuccessListener { loc ->
                    val acc = loc?.let { if (it.hasAccuracy()) it.accuracy else null }
                    if (loc != null && GeoStatus.plausible(acc)) {
                        jump(loc, "start")
                    } else {
                        // lastLocation худой/отсутствует — пробуем свежий фикс
                        // (он может оказаться правдоподобным даже при плохом кэше).
                        if (loc != null) onFusedFix(loc, "start") // лог rejected
                        runCatching {
                            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener { fresh -> jump(fresh, "start") }
                        }
                    }
                }
            }
        }
        // Камера к живой точке (follow/FAB): зум caller задает сам.
        // Вызывать только с UI-потока, tilt всегда 0. Цель — производная
        // liveLatLon (слой иначе fallback Fused): оба источника через гейт.
        fun moveToLive(zoom: Float) {
            // Живое чтение через синк: FAB и возврат follow пересинкивают тот
            // же снимок, что рисует дырку — камера и крест не разъезжаются.
            val target = syncLayerPosition() ?: liveLatLon ?: return
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
        // (first-launch-visibility, задача 1.2) Fallback Fused-подписка: слой
        // в помещении может молчать десятки секунд — экран живёт от собственной
        // подписки, пока карта видима (ON_START/ON_STOP). Первое значение —
        // lastLocation через гейт, дальше — обновления HIGH ~8 c. Приоритет
        // слоя и гейт 100 м — внутри onFusedFix. В БД подписка не пишет
        // ничего: читает только экран (спека map-render).
        val fallbackCallback = remember(context) {
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) onFusedFix(loc, "sub")
                }
            }
        }
        DisposableEffect(lifecycle, mapView, fallbackCallback) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        geoEnabled = locationEnabled()
                        runCatching {
                            client.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) onFusedFix(loc, "last")
                            }
                            val request = LocationRequest.Builder(
                                Priority.PRIORITY_HIGH_ACCURACY, 8_000L
                            )
                                .setMinUpdateIntervalMillis(5_000L)
                                .setWaitForAccurateLocation(false)
                                .build()
                            client.requestLocationUpdates(
                                request, fallbackCallback, Looper.getMainLooper()
                            )
                            // ВРЕМЕННОЕ (dev-logging): редкие события жизненного цикла.
                            DevLog.d("UI", "screen_pos", mapOf("source" to "fused", "event" to "subscribe"))
                        }.onFailure {
                            DevLog.w("UI", "screen_pos", mapOf("source" to "fused", "event" to "subscribe_failed"))
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        runCatching { client.removeLocationUpdates(fallbackCallback) }
                        DevLog.d("UI", "screen_pos", mapOf("source" to "fused", "event" to "unsubscribe"))
                    }
                    else -> Unit
                }
            }
            // addObserver синхронно достаёт текущее состояние lifecycle.
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                runCatching { client.removeLocationUpdates(fallbackCallback) }
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
        // (first-launch-visibility): тем же тиком обновляем мастер гео для чипа —
        // если настройка поменялась, пока экран открыт, MAX 30 с до перехода.
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                nowTickMs = SystemClock.elapsedRealtime()
                geoEnabled = locationEnabled()
            }
        }
        // (first-launch-visibility, полевой фикс) Живой синк снимка слоя:
        // события объекта не приходят на каждый фикс — дырка/чип/follow
        // замерзали на старом снимке, FAB читал живую позицию и камера
        // разъезжалась с крестом (в поле курсор выезжал из дырки под вуаль).
        // Тик 1 с, запись по эпсилону; экран остановлен — молчим.
        LaunchedEffect(liveView) {
            if (liveView == null) return@LaunchedEffect
            while (true) {
                delay(1_000)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continue
                syncLayerPosition()
            }
        }
        val isLiveStale = liveLatLon != null && liveLastMs > 0L &&
            (nowTickMs - liveLastMs) > TrustEngine.SILENCE_RESET_S * 1000L
        LaunchedEffect(liveView, isLiveStale) {
            val view = liveView ?: return@LaunchedEffect
            if (runCatching { view.isValid() }.getOrDefault(false).not()) return@LaunchedEffect
            // (always-red-cursor): приглушаем обе иконки — видна та,
            // которую SDK выбрал режимом PIN/ARROW.
            val opacity = if (isLiveStale) 0.5f else 1f
            runCatching { view.pin.opacity = opacity }
            runCatching { view.arrow.opacity = opacity }
        }
        // Дырки в пикселях: из памяти, проекция worldToScreen в UI-потоке.
        // Ключи — клетки + зум + target: иначе при пане одним пальцем
        // (зум тот же) дырки стоят, а карта едет под ними.
        // Север всегда сверху (remove-map-rotation): проекция по двум углам
        // точна, azimuth-ключа нет.
        // worldToScreen может вернуть null (точка за камерой) — тогда дырки
        // нет (fail-closed). Лог — след спайка 1.1–1.2.
        val holesPx: List<HolePx> = remember(cells, camZoom, camTarget, liveLatLon, presenceStuck) {
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
            val detailed = FogMask.holesForZoom(cells, camZoom, region)
            // Гистерезис (smooth-fog-zoom): переполнение считаем за MAX+1,
            // точное значение выше лимита слою не нужно; точный подсчет
            // с cap нужен только для решения об удержании пятен.
            val preciseCount = if (detailed.mode == FogMask.Mode.PRECISE) detailed.holes.size
            else FogMask.preciseHoleCount(cells, camZoom, region)
            val overflowed = detailed.mode != FogMask.Mode.PRECISE
            val wantPresence = camZoom >= FogMask.DETAIL_ZOOM &&
                FogMask.resolvePresenceStuck(
                    if (overflowed) FogMask.MAX_HOLES + 1 else preciseCount,
                    presenceStuck
                )
            if (wantPresence != presenceStuck) presenceStuck = wantPresence
            // Удержание пятен при влезающем точном (полоса 601–800): пятна
            // строим отдельно той же лесенкой; в остальных случаях результат
            // holesForZoom уже нужный (точное либо fallback-лесенка).
            // Режим показанного нужен диагностике (`presence_z` в RENDER agg).
            val shown = if (wantPresence && !overflowed)
                FogMask.presenceFallbackHoles(cells, camZoom, region)
            else detailed
            val holes = shown.holes
            val presenceZ = shown.mode.presenceZ()
            // Показ пятен вместо точного в зоне >= 13 — fallback для лога
            // (лесенка 11–13 и обзор — штатный режим, не fallback).
            val isFallback = wantPresence
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
                                maxOf(s1.x, s2.x), maxOf(s1.y, s2.y),
                                // Грубые пятна (z16 и грубее, включая родителей
                                // компакшна) — мягкие углы, см. FogMaskOverlay.
                                h.z <= FogMask.MID_PRESENCE_Z
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
            // Fallback виден только в агрегате (smooth-fog-zoom), W на него нет.
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
                    fallback = isFallback && !isOverBudget,
                    presenceZ = if (isOverBudget) 0 else presenceZ,
                    nowMono = SystemClock.elapsedRealtime()
                )
            }
            out
        }
        // (first-launch-visibility 2.1) Экранная точка fallback-курсора: та же
        // проекция worldToScreen, что у дырок — точка едет с картой при пане.
        // Ключи — как у holesPx (живая позиция, зум, target) + сам слой: объект
        // слоя есть → проекция не строится (точка слоя и есть курсор, двойной
        // точки не бывает — 2.2); слой молчит + фикса нет → точки тоже нет.
        val fallbackPx: Pair<Float, Float>? = remember(
            liveLatLon, camZoom, camTarget, layerLatLon
        ) {
            val fix = liveLatLon
            if (layerLatLon != null || fix == null) null
            else runCatching { mapView.mapWindow.worldToScreen(Point(fix.first, fix.second)) }
                .getOrNull()?.let { it.x to it.y }
        }
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // (first-launch-visibility 2.1–2.3) Fallback-курсор под вуалью: пока
            // слой молчит, та же красная точка (тот же растр, масштаб 1.5) в
            // показной дырке. При объекте слоя гаснет — на экране одна точка;
            // протухание > 2 мин — тем же liveLastMs/тиком, что у нативной.
            if (fallbackPx != null) {
                val (fx, fy) = fallbackPx
                Image(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = null,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            // Якорь в центре иконки — как anchor(0.5; 0.5) слоя.
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(
                                    fx.roundToInt() - placeable.width / 2,
                                    fy.roundToInt() - placeable.height / 2
                                )
                            }
                        }
                        .graphicsLayer(
                            // Масштаб 1.5 от базы 12dp — как IconStyle слоя.
                            scaleX = 1.5f,
                            scaleY = 1.5f,
                            alpha = if (isLiveStale) 0.5f else 1f
                        )
                )
            }
            // Маска поверх карты (личная сборка: MAY перекрывать логотип).
            FogMaskOverlay(
                holes = holesPx,
                veilColor = veilColor,
                modifier = Modifier.fillMaxSize()
            )
            // Статус-чипы слева внизу над логотипом SDK: сверху — состояние
            // геолокации (спека map-render), снизу — запись сессии. Компоновка
            // отдельной строкой, чтобы чипы не наложились (design.md D5).
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // (спека map-render) Чип состояния геолокации: объясняет, почему
                // живой точки на экране ещё нет. Четыре состояния считает
                // чистая GeoStatus.state; заглушек не видит — при них сюда не
                // попадаем (return@Scaffold выше чипа нет).
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    Text(
                        GeoStatus.label(
                            GeoStatus.state(
                                geoEnabled = geoEnabled,
                                lastFixMs = liveLastMs.takeIf { it > 0L },
                                nowMs = nowTickMs
                            )
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
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
                    // Цель — производная liveLatLon (слой иначе fallback, оба
                    // через гейт 100 м); без позиции moveToMyLocation тоже
                    // через гейт — камера без правдоподобного фикса не едет.
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

// Эпсилон живого синка позиции слоя, м: меньше — дрожание GPS будило
// рекомпозицию и follow каждый тик, больше — дырка отставала от курсора.
private const val LIVE_SYNC_EPS_M = 2.0

// Приблизительное расстояние между координатами, м (плоскость у точки).
private fun distMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val midLat = Math.toRadians((a.first + b.first) / 2.0)
    val dLat = (a.first - b.first) * 111_320.0
    val dLon = (a.second - b.second) * 111_320.0 * Math.cos(midLat)
    return Math.hypot(dLat, dLon)
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
