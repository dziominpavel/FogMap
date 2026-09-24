package ru.fogmap.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ru.fogmap.FogMapApp
import ru.fogmap.R
import ru.fogmap.data.Stats
import ru.fogmap.ui.BottomBar
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** Экран статистики (ui-dark-redesign 3.1): сетка 2x2 + hero + пустое состояние. */
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
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                ranges.forEachIndexed { i, (_, label) ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            val s = stats
            if (s == null) {
                Text("Загрузка…", modifier = Modifier.padding(16.dp))
            } else if (s.tracks == 0L && s.areaKm2 == 0.0) {
                StatsEmptyState(onCta = { nav.navigate("map") })
            } else {
                StatsContent(nav, s)
            }
        }
    }
}

@Composable
private fun StatsContent(nav: NavController, s: Stats) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Card(Modifier.fillMaxWidth()) {
                Image(
                    painterResource(R.drawable.img_hero_fog),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }
        // Вход в прогресс по регионам (add-region-progress 3.3).
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            RegionsEntryCard(onClick = { nav.navigate("regions") })
        }
        // Вход в достижения (add-achievements 3.4).
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            AchievementsEntryCard(onClick = { nav.navigate("achievements") })
        }
        items(
            listOf(
                "Площадь" to "%.2f км²".format(s.areaKm2),
                "Дистанция" to "%.1f км".format(s.distanceM / 1000),
                "Треки" to "${s.tracks}",
                "Время в пути" to "${s.timeS / 60} мин"
            ) + if (s.rejected > 0) listOf("Отброшено точек" to "${s.rejected}") else emptyList()
        ) { (label, value) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(value, style = MaterialTheme.typography.headlineSmall)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Вход в прогресс по регионам (add-region-progress 3.3). */
@Composable
private fun RegionsEntryCard(onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Прогресс по регионам", style = MaterialTheme.typography.titleSmall)
                Text(
                    "13 регионов: города, области, республика",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}

/** Вход в достижения (add-achievements 3.4). */
@Composable
private fun AchievementsEntryCard(onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Достижения", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Регионы, площадь, серии дней",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}

/** Пустое состояние с иллюстрацией и CTA (spec app-shell). */
@Composable
private fun StatsEmptyState(onCta: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painterResource(R.drawable.img_empty_stats),
            contentDescription = null,
            modifier = Modifier.size(200.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Пока пусто", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Включите гео и погуляйте — здесь появятся площадь, дистанция и треки",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCta) { Text("Открыть карту") }
    }
}
