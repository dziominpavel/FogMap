package ru.fogmap.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import ru.fogmap.FogMapApp
import ru.fogmap.diag.DevLog
import ru.fogmap.tracking.TrackingService

/**
 * Нижняя навигация (ui-dark-redesign 2.1): иконки Material + подписи,
 * точка-индикатор активной записи на разделе карты.
 */
@Composable
fun BottomBar(nav: NavController, current: String) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    val enabled by app.container.settingsRepository.recordingEnabled.collectAsState(initial = true)
    // Запись активна: тумблер включён и гео разрешено (MapScreen в этом
    // состоянии автостартует TrackingService).
    val recording = enabled && TrackingService.canTrack(context)

    val entries = listOf(
        Triple("map", "Карта", Icons.Filled.Map),
        Triple("stats", "Статистика", Icons.Filled.BarChart),
        Triple("history", "История", Icons.Filled.History),
        Triple("settings", "Настройки", Icons.Filled.Settings)
    )
    NavigationBar {
        for ((route, label, icon) in entries) {
            NavigationBarItem(
                selected = current == route,
                onClick = {
                    if (current != route) {
                        // ВРЕМЕННОЕ (dev-logging): переход навигации.
                        DevLog.d("UI", "nav", mapOf("from" to current, "to" to route))
                        nav.navigate(route)
                    }
                },
                icon = {
                    if (route == "map" && recording) {
                        BadgedBox(badge = { Badge() }) {
                            Icon(icon, contentDescription = label)
                        }
                    } else {
                        Icon(icon, contentDescription = label)
                    }
                },
                label = { Text(label) }
            )
        }
    }
}
