package ru.fogmap

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.LegacyPrefsCleanup

/**
 * change recording-mode, задача 8.3: ключи снятого эко-режима удаляются,
 * а живые настройки (тумблер записи, тема) остаются нетронутыми.
 */
class LegacyPrefsCleanupTest {

    @Test
    fun `удаляет все снятые ключи`() {
        val prefs = preferencesOf(
            stringPreferencesKey(LegacyPrefsCleanup.ECO_PROFILE) to "STANDBY",
            booleanPreferencesKey(LegacyPrefsCleanup.ECO_MODE) to true,
            doublePreferencesKey(LegacyPrefsCleanup.ANCHOR_LAT) to 53.9,
            doublePreferencesKey(LegacyPrefsCleanup.ANCHOR_LON) to 27.56,
            longPreferencesKey(LegacyPrefsCleanup.ANCHOR_TIME) to 1_000L
        ).toMutablePreferences()
        assertEquals(5, LegacyPrefsCleanup.clean(prefs))
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun `живые ключи не трогает`() {
        val prefs = preferencesOf(
            booleanPreferencesKey("recording_enabled") to true,
            stringPreferencesKey("theme_mode") to "dark",
            stringPreferencesKey(LegacyPrefsCleanup.ECO_PROFILE) to "ACTIVE"
        ).toMutablePreferences()
        assertEquals(1, LegacyPrefsCleanup.clean(prefs))
        assertTrue(prefs.contains(booleanPreferencesKey("recording_enabled")))
        assertTrue(prefs.contains(stringPreferencesKey("theme_mode")))
        assertFalse(prefs.contains(stringPreferencesKey(LegacyPrefsCleanup.ECO_PROFILE)))
    }

    @Test
    fun `повторный вызов безопасен`() {
        val prefs = preferencesOf(booleanPreferencesKey(LegacyPrefsCleanup.ECO_MODE) to false)
            .toMutablePreferences()
        assertEquals(1, LegacyPrefsCleanup.clean(prefs))
        assertEquals(0, LegacyPrefsCleanup.clean(prefs))
        assertEquals(5, LegacyPrefsCleanup.KEYS.size)
    }
}
