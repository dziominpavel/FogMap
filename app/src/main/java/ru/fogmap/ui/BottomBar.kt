package ru.fogmap.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@Composable
fun BottomBar(nav: NavController, current: String) {
    NavigationBar {
        for ((route, label) in listOf(
            "map" to "Карта", "stats" to "Статистика",
            "history" to "История", "settings" to "Настройки"
        )) {
            NavigationBarItem(
                selected = current == route,
                onClick = { if (current != route) nav.navigate(route) },
                icon = {},
                label = { Text(label) }
            )
        }
    }
}
