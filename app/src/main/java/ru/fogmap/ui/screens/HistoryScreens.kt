package ru.fogmap.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.launch
import ru.fogmap.FogMapApp
import ru.fogmap.data.db.TrackEntity
import ru.fogmap.ui.BottomBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** История треков (spec history, задача 5.2): список + детали + удаление + переименование. */
@Composable
fun HistoryScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    LaunchedEffect(Unit) { tracks = app.container.trackRepository.allTracks() }
    Scaffold(bottomBar = { BottomBar(nav, "history") }) { pad ->
        if (tracks.isEmpty()) {
            Column(Modifier.padding(pad).padding(16.dp)) {
                Text("Треков пока нет")
                Text("Включите гео и погуляйте — треки появятся сами")
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(tracks, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.navigate("history/${t.id}") }
                            .padding(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name)
                            Text(
                                SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))
                                    .format(Date(t.startedAt)) +
                                    " · ${"%.1f".format(t.distanceM / 1000)} км"
                            )
                        }
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
    LaunchedEffect(trackId) {
        val all = app.container.trackRepository.allTracks()
        track = all.firstOrNull { it.id == trackId }
        points = app.container.trackRepository.pointsOf(trackId)
            .map { Point(it.lat, it.lon) }
    }
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text(track?.name ?: "Трек")
            if (points.isEmpty()) {
                Text("Точек нет")
            } else if (!(context.applicationContext as FogMapApp).isMapKitReady) {
                Text("Карта недоступна без API-ключа, точек: ${points.size}")
            } else {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            onStart()
                            mapWindow.map.move(CameraPosition(points.first(), 14f, 0f, 0f))
                            mapWindow.map.mapObjects.addPolyline(Polyline(points))
                        }
                    },
                    onRelease = { it.onStop() },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            Row {
                Button(onClick = { renameOpen = true }) { Text("Переименовать") }
                Button(onClick = { deleteOpen = true }) { Text("Удалить") }
            }
            Text("Удаление трека не закрывает туман и не уменьшает площадь")
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
