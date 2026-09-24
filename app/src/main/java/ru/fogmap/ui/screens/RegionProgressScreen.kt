package ru.fogmap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ru.fogmap.FogMapApp
import ru.fogmap.data.RegionProgress
import ru.fogmap.region.RegionType

/**
 * Прогресс по регионам (add-region-progress): группы Города/Области/Республика,
 * карточки с км² и % (производная), сортировка по убыванию процента.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionProgressScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    var grouped by remember {
        mutableStateOf<Map<RegionType, List<RegionProgress>>>(emptyMap())
    }
    LaunchedEffect(Unit) {
        val items = app.container.regionProgressRepository.progress()
        grouped = app.container.regionProgressRepository.grouped(items)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Прогресс по регионам") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { pad ->
        if (grouped.isEmpty()) {
            Text(
                "Загрузка…",
                modifier = Modifier.padding(pad).padding(16.dp)
            )
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((type, list) in grouped) {
                    item(key = "h_${type.name}") {
                        Text(
                            groupTitle(type),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.region.id }) { p ->
                        RegionCard(p)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun groupTitle(type: RegionType): String = when (type) {
    RegionType.CITY -> "Города"
    RegionType.OBLAST -> "Области"
    RegionType.REPUBLIC -> "Республика"
}

@Composable
private fun RegionCard(p: RegionProgress) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.region.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "%.1f%%".format(p.percent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (p.percent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "%.2f км²".format(p.openAreaKm2),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "из %.0f км²".format(p.region.totalAreaKm2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
