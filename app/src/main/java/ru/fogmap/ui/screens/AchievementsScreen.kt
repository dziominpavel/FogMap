package ru.fogmap.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import ru.fogmap.achievement.AchievementCategory
import ru.fogmap.achievement.AchievementDef
import ru.fogmap.achievement.Achievements
import ru.fogmap.data.db.AchievementEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AchievementRow(
    val def: AchievementDef,
    val unlockedAt: Long?
)

/**
 * Экран достижений (add-achievements): группы Регионы / Площадь / Серии;
 * unlocked с датой, locked с условием. Toast на новую разблокировку — в сессии.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as FogMapApp
    var grouped by remember {
        mutableStateOf<Map<AchievementCategory, List<AchievementRow>>>(emptyMap())
    }
    // Тост при разблокировке, пока открыт экран или приложение в сессии.
    LaunchedEffect(Unit) {
        app.container.achievementRepository.unlocked.collect { def ->
            Toast.makeText(context, "Достижение: ${def.title}", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        val unlocked = app.container.achievementRepository.getAll()
            .associate { it.id to it.unlockedAt }
        grouped = Achievements.all()
            .map { AchievementRow(it, unlocked[it.id]) }
            .groupBy { it.def.category }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Достижения") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { pad ->
        if (grouped.isEmpty()) {
            Text("Загрузка…", modifier = Modifier.padding(pad).padding(16.dp))
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (cat in listOf(
                    AchievementCategory.REGION,
                    AchievementCategory.AREA,
                    AchievementCategory.STREAK
                )) {
                    val list = grouped[cat].orEmpty()
                    if (list.isEmpty()) continue
                    item(key = "h_${cat.name}") {
                        Text(
                            categoryTitle(cat),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.def.id }) { row ->
                        AchievementCard(row)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun categoryTitle(cat: AchievementCategory): String = when (cat) {
    AchievementCategory.REGION -> "Регионы"
    AchievementCategory.AREA -> "Площадь"
    AchievementCategory.STREAK -> "Серии"
}

@Composable
private fun AchievementCard(row: AchievementRow) {
    val unlocked = row.unlockedAt != null
    Card(
        Modifier.fillMaxWidth(),
        colors = if (unlocked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.def.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (unlocked) {
                    val date = SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru"))
                        .format(Date(row.unlockedAt!!))
                    Text(
                        "Открыто $date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        row.def.condition,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
