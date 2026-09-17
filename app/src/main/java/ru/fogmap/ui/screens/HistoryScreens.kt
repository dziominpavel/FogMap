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
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.PrefsKeys
import ru.fogmap.data.ThemeModes
import ru.fogmap.data.db.TrackEntity
import ru.fogmap.map.FogLayer
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
    LaunchedEffect(Unit) { tracks = app.container.trackRepository.allTracks() }
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
                                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))
                                        .format(Date(t.startedAt)) +
                                        " · ${"%.1f".format(t.distanceM / 1000)} км"
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

@Composable
fun HistoryDetailScreen(nav: NavController, trackId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var track by remember { mutableStateOf<TrackEntity?>(null) }
    var points by remember { mutableStateOf<List<Point>>(emptyList()) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    // Мини-карта трека следует ночному режиму оболочки (4.2).
    val detailPrefs by app.container.dataStore.data.collectAsState(initial = preferencesOf())
    val detailNight = isDarkTheme(
        detailPrefs[PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT,
        isSystemInDarkTheme()
    ) && !FogLayer.FORCE_DAY_MAP
    LaunchedEffect(trackId) {
        val all = app.container.trackRepository.allTracks()
        track = all.firstOrNull { it.id == trackId }
        points = app.container.trackRepository.pointsOf(trackId)
            .map { Point(it.lat, it.lon) }
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
                            SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))
                                .format(Date(t.startedAt)) +
                                " · ${"%.1f".format(t.distanceM / 1000)} км",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (points.isEmpty()) {
                Text("Точек нет")
            } else if (!(context.applicationContext as FogMapApp).isMapKitReady) {
                Text("Карта недоступна без API-ключа, точек: ${points.size}")
            } else {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            onStart()
                            runCatching { mapWindow.map.setNightModeEnabled(detailNight) }
                            mapWindow.map.move(CameraPosition(points.first(), 14f, 0f, 0f))
                            mapWindow.map.mapObjects.addPolyline(Polyline(points))
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
