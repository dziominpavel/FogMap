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
    /**
     * Тумблер «Запись» (change recording-mode): true — пишем трек и туман.
     * Заменяет инвертированный `paused`; старый ключ читается только миграцией.
     */
    val RECORDING_ENABLED = booleanPreferencesKey("recording_enabled")
    /** Только для миграции (RecordingMigration). Новый код его не пишет и не читает. */
    val LEGACY_PAUSED = booleanPreferencesKey("paused")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** Тема оболочки: dark|light, default dark (remove-system-theme; system удалён). */
    val THEME_MODE = stringPreferencesKey("theme_mode")
    /** ВРЕМЕННОЕ (dev-logging): вкл/выкл диагностики, default true до стабилизации. */
    val DIAG_ENABLED = booleanPreferencesKey("diag_enabled")
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

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val db: AppDatabase
) {
    /**
     * Тумблер записи (change recording-mode, D2). Значение при чтении
     * нормализуется через [RecordingMigration] и один раз записывается:
     * старая установка получает `!paused`, чистая — `true`.
     */
    val recordingEnabled: Flow<Boolean> = store.data.transform { prefs ->
        val raw = prefs[PrefsKeys.RECORDING_ENABLED]
        val resolved = RecordingMigration.resolve(raw, prefs[PrefsKeys.LEGACY_PAUSED])
        emit(resolved)
        if (raw != resolved) {
            store.edit { it[PrefsKeys.RECORDING_ENABLED] = resolved }
        }
    }
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
    /** Границы регионов на карте (add-region-borders), default false. */
    val regionBorders: Flow<Boolean> =
        store.data.map { it[PrefsKeys.REGION_BORDERS] ?: false }

    /** Тумблер записи (change recording-mode): единственное место переключения. */
    suspend fun setRecordingEnabled(v: Boolean) {
        store.edit { it[PrefsKeys.RECORDING_ENABLED] = v }
    }
    suspend fun setOnboardingDone() { store.edit { it[PrefsKeys.ONBOARDING_DONE] = true } }
    suspend fun setThemeMode(v: String) {
        require(v in setOf(ThemeModes.DARK, ThemeModes.LIGHT))
        store.edit { it[PrefsKeys.THEME_MODE] = v }
    }

    /** ВРЕМЕННОЕ (dev-logging). */
    suspend fun setDiagEnabled(v: Boolean) { store.edit { it[PrefsKeys.DIAG_ENABLED] = v } }

    /** Границы регионов на карте (add-region-borders): слой отрисовки вкл/выкл. */
    suspend fun setRegionBorders(v: Boolean) { store.edit { it[PrefsKeys.REGION_BORDERS] = v } }

    /**
     * Удаляет ключи снятого эко-режима (change recording-mode, 8.3).
     * @return сколько ключей удалено (0 — уже чисто).
     */
    suspend fun cleanLegacyEcoKeys(): Int {
        var removed = 0
        store.edit { prefs -> removed = LegacyPrefsCleanup.clean(prefs) }
        return removed
    }

    /** Разрешение на уведомления уже спрашивали (5.5): повторно не спрашиваем. */
    suspend fun setNotifAsked() { store.edit { it[PrefsKeys.NOTIF_ASKED] = true } }

    suspend fun resetAll(fog: FogRepository) = fog.clearAll()
}
