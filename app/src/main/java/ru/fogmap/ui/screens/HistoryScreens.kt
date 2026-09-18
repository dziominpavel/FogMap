package ru.fogmap.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.preferencesOf
import androidx.navigation.NavController
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.LineStyle
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.ThemeModes
import ru.fogmap.data.db.TrackEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.map.FogMask
import ru.fogmap.ui.BottomBar
import ru.fogmap.ui.theme.isDarkTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** История треков (ui-dark-redesign 3.2): карточки + единый стиль деталей. */
@Composable
fun HistoryScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    var tracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    // Диагностика дня (tracking-reliability 3.2): пустота объясняется отбросами.
    var rejectedToday by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        tracks = app.container.trackRepository.allTracks()
        rejectedToday = app.container.statsRepository
            .rejectedBreakdown(ru.fogmap.data.StatsRepository.dayRange())
    }
    Scaffold(bottomBar = { BottomBar(nav, "history") }) { pad ->
        if (tracks.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painterResource(R.drawable.img_empty_history),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Треков пока нет", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Включите гео и погуляйте — треки появятся сами",
                    style = MaterialTheme.typography.bodyMedium
                )
                val rejTotal = rejectedToday.values.sum()
                if (rejTotal > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Сегодня отброшено точек: $rejTotal (" +
                            rejectedToday.entries
                                .filter { it.value > 0 }
                                .joinToString { "${it.key}: ${it.value}" } +
                            "). Запись идет, но GPS режет точки.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                items(tracks, key = { it.id }) { t ->
                    Card(onClick = { nav.navigate("history/${t.id}") }) {
                        ListItem(
                            headlineContent = { Text(t.name) },
                            supportingContent = {
                                Text(
                                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
                                        .format(Date(t.startedAt)) +
                                        " · ${"%.1f".format(t.distanceM / 1000)} км" +
                                        " · ${trackDurationMin(t.startedAt, t.finishedAt)} мин" +
                                        " · ${t.pointsCount} тчк"
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Route, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Длительность трека в минутах по startedAt/finishedAt (tracking-reliability 1.2). */
internal fun trackDurationMin(startedAt: Long, finishedAt: Long): Long =
    ((finishedAt - startedAt).coerceAtLeast(0) / 60_000)

/** Цвета линии трека по доверию (trust-v2 3.2). */
private const val TRUSTED_LINE = 0xFF1E88E5.toInt() // синий: доверенные
private const val UNTRUSTED_LINE = 0xFF9E9E9E.toInt() // серый: недоверенные

/**
 * Прогоны доверия для цветной линии (trust-v2 3.2): отрезки [start, end)
 * с флагом trusted. Одиночные точки приклеиваются к предыдущему прогону
 * (точка линию не дает, мерцание цвета ни к чему). Чистое, тестируется.
 */
internal fun trustRuns(trusts: List<Int>, open: Int): List<Triple<Int, Int, Boolean>> {
    if (trusts.isEmpty()) return emptyList()
    val cls = trusts.map { it >= open }
    val starts = ArrayList<Int>()
    starts.add(0)
    for (i in 1 until cls.size) if (cls[i] != cls[i - 1]) starts.add(i)
    val out = ArrayList<Triple<Int, Int, Boolean>>()
    for (k in starts.indices) {
        val s = starts[k]
        val e = if (k + 1 < starts.size) starts[k + 1] else cls.size
        if (e - s < 2 && out.isNotEmpty()) {
            val last = out.removeAt(out.size - 1)
            out.add(Triple(last.first, e, last.third))
        } else {
            out.add(Triple(s, maxOf(e, s + 1), cls[s]))
        }
    }
    return out
}

/**
 * Секции дня по часам точки (trust-v2 3.1): представление внутри чанка,
 * хранение не режется. Ночь 23–4, утро 5–10, день 11–16, вечер 17–22.
 */
internal fun daySections(times: List<Long>): List<Pair<String, Int>> {
    val zone = java.time.ZoneId.systemDefault()
    val hours = times.map {
        java.time.Instant.ofEpochMilli(it).atZone(zone).hour
    }
    fun count(r: IntRange) = hours.count { it in r }
    return listOf(
        "Утро" to count(5..10),
        "День" to count(11..16),
        "Вечер" to count(17..22),
        "Ночь" to (count(23..23) + count(0..4))
    ).filter { it.second > 0 }
}

@Composable
fun HistoryDetailScreen(nav: NavController, trackId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var track by remember { mutableStateOf<TrackEntity?>(null) }
    var pointEnts by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }
    val points = pointEnts.map { Point(it.lat, it.lon) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    // Мини-карта трека следует ночному режиму оболочки (4.2).
    val detailPrefs by app.container.dataStore.data.collectAsState(initial = preferencesOf())
    val detailNight = isDarkTheme(
        detailPrefs[PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT,
        isSystemInDarkTheme()
    ) && !FogMask.FORCE_DAY_MAP
    LaunchedEffect(trackId) {
        val all = app.container.trackRepository.allTracks()
        track = all.firstOrNull { it.id == trackId }
        pointEnts = app.container.trackRepository.pointsOf(trackId)
    }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(track?.name ?: "Трек", style = MaterialTheme.typography.titleLarge)
                    val t = track
                    if (t != null) {
                        Text(
                            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
                                .format(Date(t.startedAt)) +
                                " · ${"%.1f".format(t.distanceM / 1000)} км" +
                                " · ${trackDurationMin(t.startedAt, t.finishedAt)} мин" +
                                " · ${t.pointsCount} тчк",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val sections = remember(pointEnts) {
                            daySections(pointEnts.map { it.time })
                        }
                        if (sections.isNotEmpty()) {
                            Text(
                                sections.joinToString(" · ") { "${it.first}: ${it.second}" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            if (points.isEmpty()) {
                Text("Точек нет")
            } else if (!(context.applicationContext as FogMapApp).isMapKitReady) {
                Text("Карта недоступна без API-ключа, точек: ${points.size}")
            } else {
                // Цветная линия по доверию (trust-v2 3.2): доверенные — синим,
                // недоверенные — серым тоньше. Только история, на карту не тащим.
                val runs = remember(pointEnts) {
                    trustRuns(
                        pointEnts.map { it.trust },
                        ru.fogmap.fog.FogGrid.TRUST_OPEN
                    )
                }
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            onStart()
                            runCatching { mapWindow.map.setNightModeEnabled(detailNight) }
                            mapWindow.map.move(CameraPosition(points.first(), 14f, 0f, 0f))
                            for ((s, e, trusted) in runs) {
                                if (e - s < 2) continue
                                val line = mapWindow.map.mapObjects.addPolyline(
                                    Polyline(points.subList(s, e.coerceAtMost(points.size)))
                                )
                                // Доверенные — синим пожирнее, недоверенные —
                                // серым пунктиром (LineStyle, не deprecated).
                                if (trusted) {
                                    line.setStrokeColor(TRUSTED_LINE)
                                    line.style = LineStyle().apply { strokeWidth = 5f }
                                } else {
                                    line.setStrokeColor(UNTRUSTED_LINE)
                                    line.style = LineStyle().apply {
                                        strokeWidth = 3f
                                        dashLength = 8f
                                        gapLength = 6f
                                    }
                                }
                            }
                        }
                    },
                    onRelease = { it.onStop() },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { renameOpen = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Переименовать")
                }
                OutlinedButton(onClick = { deleteOpen = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Удалить")
                }
            }
            Text(
                "Удаление трека не закрывает туман и не уменьшает площадь",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (renameOpen) {
        var name by remember(track?.name) { mutableStateOf(track?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.container.trackRepository.rename(trackId, name)
                        track = track?.copy(name = name)
                        renameOpen = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Отмена") } },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Название") }) }
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.container.trackRepository.deleteTrack(trackId)
                        deleteOpen = false
                        nav.popBackStack()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Отмена") } },
            text = { Text("Трек исчезнет из истории, туман и площадь не изменятся") }
        )
    }
}
