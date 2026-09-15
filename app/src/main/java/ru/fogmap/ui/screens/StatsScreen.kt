package ru.fogmap.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import ru.fogmap.FogMapApp
import ru.fogmap.data.Stats
import ru.fogmap.ui.BottomBar
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** Экран статистики (spec statistics, задача 5.1): значения из счетчиков. */
@Composable
fun StatsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    var tab by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    val ranges = remember {
        val date = LocalDate.now()
        val week = date.get(WeekFields.of(Locale.getDefault()).weekOfYear())
        listOf("day_$date" to "День", "week_${week}-${date.year}" to "Неделя", "all" to "Всё время")
    }
    LaunchedEffect(tab) {
        stats = app.container.statsRepository.stats(ranges[tab].first)
    }
    Scaffold(bottomBar = { BottomBar(nav, "stats") }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                ranges.forEachIndexed { i, (_, label) ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            val s = stats
            if (s == null) {
                Text("Загрузка…")
            } else if (s.tracks == 0L && s.areaKm2 == 0.0) {
                Text("Пока пусто — включите гео и погуляйте")
            } else {
                Text("Площадь: ${"%.2f".format(s.areaKm2)} км²")
                Text("Дистанция: ${"%.1f".format(s.distanceM / 1000)} км")
                Text("Треки: ${s.tracks}")
                Text("Время в пути: ${s.timeS / 60} мин")
            }
        }
    }
}
