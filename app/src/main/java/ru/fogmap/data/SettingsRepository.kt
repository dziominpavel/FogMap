package ru.fogmap.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import ru.fogmap.data.db.AppDatabase

object PrefsKeys {
    val PAUSED = booleanPreferencesKey("paused")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** Тема оболочки: dark|light, default dark (remove-system-theme; system удалён). */
    val THEME_MODE = stringPreferencesKey("theme_mode")
    /** ВРЕМЕННОЕ (dev-logging): вкл/выкл диагностики, default true до стабилизации. */
    val DIAG_ENABLED = booleanPreferencesKey("diag_enabled")
    /** Эко-режим трекинга (battery-eco 1.1): дев-переключалка base/eco, default base. */
    val ECO_MODE = booleanPreferencesKey("eco_mode")
    /** Последний эко-профиль (battery-eco 2.4): ACTIVE/STANDBY/BURST для рестарта. */
    val ECO_PROFILE = stringPreferencesKey("eco_profile")
    /**
     * Якорь STANDBY (wake-balance-parking 2.1): переживает убийство/ребут,
     * первое смещение после рестарта считается от него.
     */
    val ANCHOR_LAT = doublePreferencesKey("standby_anchor_lat")
    val ANCHOR_LON = doublePreferencesKey("standby_anchor_lon")
    val ANCHOR_TIME = longPreferencesKey("standby_anchor_time")
    /** Границы регионов на карте (add-region-borders): default false. */
    val REGION_BORDERS = booleanPreferencesKey("region_borders")
    /** POST_NOTIFICATIONS уже спрашивали (fix-track-gap-visibility 5.5): не повторяем. */
    val NOTIF_ASKED = booleanPreferencesKey("notif_asked")
}

object ThemeModes {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val DEFAULT = DARK

    /**
     * Нормализация при чтении (remove-system-theme, design D1): `light` -> `light`,
     * всё остальное (включая устаревшее `system` и мусор) -> `dark`.
     */
    fun normalize(value: String?): String =
        if (value == LIGHT) LIGHT else DARK
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
    /**
     * Тема только `dark`/`light` (remove-system-theme, design D1): недопустимое
     * значение (`system`, мусор) нормализуется в `dark` и перезаписывается в
     * DataStore при чтении; отсутствие выбора -> `dark` (без записи).
     */
    val themeMode: Flow<String> = store.data.transform { prefs ->
        val raw = prefs[PrefsKeys.THEME_MODE]
        val normalized = ThemeModes.normalize(raw)
        emit(normalized)
        if (raw != null && raw != normalized) {
            store.edit { it[PrefsKeys.THEME_MODE] = normalized }
        }
    }
    /** ВРЕМЕННОЕ (dev-logging). */
    val diagEnabled: Flow<Boolean> = store.data.map { it[PrefsKeys.DIAG_ENABLED] ?: true }
    /** Эко-режим (battery-eco 1.1): false = base, true = eco. */
    val ecoMode: Flow<Boolean> = store.data.map { it[PrefsKeys.ECO_MODE] ?: false }
    /** Последний эко-профиль (battery-eco 2.4). */
    val ecoProfile: Flow<String> =
        store.data.map { it[PrefsKeys.ECO_PROFILE] ?: EcoProfiles.ACTIVE }
    /** Границы регионов на карте (add-region-borders), default false. */
    val regionBorders: Flow<Boolean> =
        store.data.map { it[PrefsKeys.REGION_BORDERS] ?: false }

    suspend fun setPaused(v: Boolean) { store.edit { it[PrefsKeys.PAUSED] = v } }
    suspend fun setOnboardingDone() { store.edit { it[PrefsKeys.ONBOARDING_DONE] = true } }
    suspend fun setThemeMode(v: String) {
        require(v in setOf(ThemeModes.DARK, ThemeModes.LIGHT))
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

    /** Якорь STANDBY (wake-balance-parking 2.1): persist + восстановление. */
    suspend fun setStandbyAnchor(lat: Double, lon: Double, timeMs: Long) {
        store.edit {
            it[PrefsKeys.ANCHOR_LAT] = lat
            it[PrefsKeys.ANCHOR_LON] = lon
            it[PrefsKeys.ANCHOR_TIME] = timeMs
        }
    }

    /** Границы регионов на карте (add-region-borders): слой отрисовки вкл/выкл. */
    suspend fun setRegionBorders(v: Boolean) { store.edit { it[PrefsKeys.REGION_BORDERS] = v } }

    /** Разрешение на уведомления уже спрашивали (5.5): повторно не спрашиваем. */
    suspend fun setNotifAsked() { store.edit { it[PrefsKeys.NOTIF_ASKED] = true } }

    suspend fun resetAll(fog: FogRepository) = fog.clearAll()
}
