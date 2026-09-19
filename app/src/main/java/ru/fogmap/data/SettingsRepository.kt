package ru.fogmap.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.fogmap.data.db.AppDatabase

object PrefsKeys {
    val PAUSED = booleanPreferencesKey("paused")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** Тема оболочки: dark|light|system, default dark (ui-dark-redesign 1.1). */
    val THEME_MODE = stringPreferencesKey("theme_mode")
    /** ВРЕМЕННОЕ (dev-logging): вкл/выкл диагностики, default true до стабилизации. */
    val DIAG_ENABLED = booleanPreferencesKey("diag_enabled")
}

object ThemeModes {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"
    const val DEFAULT = DARK
}

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val db: AppDatabase
) {
    val paused: Flow<Boolean> = store.data.map { it[PrefsKeys.PAUSED] ?: false }
    val onboardingDone: Flow<Boolean> = store.data.map { it[PrefsKeys.ONBOARDING_DONE] ?: false }
    val themeMode: Flow<String> =
        store.data.map { it[PrefsKeys.THEME_MODE] ?: ThemeModes.DEFAULT }
    /** ВРЕМЕННОЕ (dev-logging). */
    val diagEnabled: Flow<Boolean> = store.data.map { it[PrefsKeys.DIAG_ENABLED] ?: true }

    suspend fun setPaused(v: Boolean) { store.edit { it[PrefsKeys.PAUSED] = v } }
    suspend fun setOnboardingDone() { store.edit { it[PrefsKeys.ONBOARDING_DONE] = true } }
    suspend fun setThemeMode(v: String) {
        require(v in setOf(ThemeModes.DARK, ThemeModes.LIGHT, ThemeModes.SYSTEM))
        store.edit { it[PrefsKeys.THEME_MODE] = v }
    }

    /** ВРЕМЕННОЕ (dev-logging). */
    suspend fun setDiagEnabled(v: Boolean) { store.edit { it[PrefsKeys.DIAG_ENABLED] = v } }

    suspend fun resetAll(fog: FogRepository) = fog.clearAll()
}
