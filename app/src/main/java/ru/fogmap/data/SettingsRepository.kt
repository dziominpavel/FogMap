package ru.fogmap.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.fogmap.data.db.AppDatabase

object PrefsKeys {
    val PAUSED = booleanPreferencesKey("paused")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
}

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val db: AppDatabase
) {
    val paused: Flow<Boolean> = store.data.map { it[PrefsKeys.PAUSED] ?: false }
    val onboardingDone: Flow<Boolean> = store.data.map { it[PrefsKeys.ONBOARDING_DONE] ?: false }

    suspend fun setPaused(v: Boolean) { store.edit { it[PrefsKeys.PAUSED] = v } }
    suspend fun setOnboardingDone() { store.edit { it[PrefsKeys.ONBOARDING_DONE] = true } }

    suspend fun resetAll(fog: FogRepository) = fog.clearAll()
}
