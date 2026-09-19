package ru.fogmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yandex.mapkit.MapKitFactory
import ru.fogmap.data.ThemeModes
import ru.fogmap.ui.screens.DiagDiagnosticsScreen
import ru.fogmap.ui.screens.HistoryDetailScreen
import ru.fogmap.ui.screens.HistoryScreen
import ru.fogmap.ui.screens.MapScreen
import ru.fogmap.ui.screens.OnboardingScreen
import ru.fogmap.ui.screens.SettingsScreen
import ru.fogmap.ui.screens.StatsScreen
import ru.fogmap.ui.theme.FogMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Тема из DataStore: смена применяется рекомпозицией, без перезапуска.
            val app = application as FogMapApp
            val themeMode by app.container.settingsRepository.themeMode
                .collectAsState(initial = ThemeModes.DEFAULT)
            FogMapTheme(themeMode) {
                FogMapNav()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { MapKitFactory.getInstance().onStart() }
    }

    override fun onStop() {
        runCatching { MapKitFactory.getInstance().onStop() }
        super.onStop()
    }
}

@Composable
fun FogMapNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "map") {
        composable("map") { MapScreen(nav) }
        composable("stats") { StatsScreen(nav) }
        composable("history") { HistoryScreen(nav) }
        composable("history/{id}") { backStack ->
            HistoryDetailScreen(nav, backStack.arguments?.getString("id")?.toLongOrNull() ?: -1)
        }
        composable("settings") { SettingsScreen(nav) }
        composable("onboarding") { OnboardingScreen(nav) }
        // ВРЕМЕННОЕ (dev-logging): экран диагностики, удалить вместе с change.
        composable("diagnostics") { DiagDiagnosticsScreen(nav) }
    }
}
