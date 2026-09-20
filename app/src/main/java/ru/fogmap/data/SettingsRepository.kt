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
    /** Эко-режим трекинга (battery-eco 1.1): дев-переключалка base/eco, default base. */
    val ECO_MODE = booleanPreferencesKey("eco_mode")
    /** Последний эко-профиль (battery-eco 2.4): ACTIVE/STANDBY/BURST для рестарта. */
    val ECO_PROFILE = stringPreferencesKey("eco_profile")
}

object ThemeModes {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"
    const val DEFAULT = DARK
}

/** Эко-профили опроса (battery-eco 2.1): имена для DataStore и логов. */
object EcoProfiles {
    const val ACTIVE = "ACTIVE"
    const val STANDBY = "STANDBY"
    const val BURST = "BURST"
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
    /** Эко-режим (battery-eco 1.1): false = base, true = eco. */
    val ecoMode: Flow<Boolean> = store.data.map { it[PrefsKeys.ECO_MODE] ?: false }
    /** Последний эко-профиль (battery-eco 2.4). */
    val ecoProfile: Flow<String> =
        store.data.map { it[PrefsKeys.ECO_PROFILE] ?: EcoProfiles.ACTIVE }

    suspend fun setPaused(v: Boolean) { store.edit { it[PrefsKeys.PAUSED] = v } }
    suspend fun setOnboardingDone() { store.edit { it[PrefsKeys.ONBOARDING_DONE] = true } }
    suspend fun setThemeMode(v: String) {
        require(v in setOf(ThemeModes.DARK, ThemeModes.LIGHT, ThemeModes.SYSTEM))
        store.edit { it[PrefsKeys.THEME_MODE] = v }
    }

    /** ВРЕМЕННОЕ (dev-logging). */
    suspend fun setDiagEnabled(v: Boolean) { store.edit { it[PrefsKeys.DIAG_ENABLED] = v } }

    /** Эко-режим (battery-eco 1.1): дев-переключалка, трек не сбрасывает. */
    suspend fun setEcoMode(v: Boolean) { store.edit { it[PrefsKeys.ECO_MODE] = v } }

    /** Последний эко-профиль (battery-eco 2.4). */
    suspend fun setEcoProfile(v: String) {
        require(v in setOf(EcoProfiles.ACTIVE, EcoProfiles.STANDBY, EcoProfiles.BURST))
        store.edit { it[PrefsKeys.ECO_PROFILE] = v }
    }

    suspend fun resetAll(fog: FogRepository) = fog.clearAll()
}
