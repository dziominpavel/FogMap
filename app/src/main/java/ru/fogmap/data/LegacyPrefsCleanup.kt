package ru.fogmap.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Чистка ключей снятого эко-режима (change recording-mode, задача 8.3).
 *
 * Профили, якорь сна и старый тумблер эко больше не читаются: оставленные
 * ключи — это ложь в хранилище, по которой следующий разработчик сделает
 * неверный вывод. Удаляем один раз при старте; отсутствие ключей не ошибка.
 *
 * Чистая часть (имена и удаление) тестируется на JVM без Android.
 */
object LegacyPrefsCleanup {

    const val ECO_PROFILE = "eco_profile"
    const val ECO_MODE = "eco_mode"
    const val ANCHOR_LAT = "standby_anchor_lat"
    const val ANCHOR_LON = "standby_anchor_lon"
    const val ANCHOR_TIME = "standby_anchor_time"

    /** Все имена снятых ключей — контракт для теста и для чтения кода. */
    val KEYS = listOf(ECO_PROFILE, ECO_MODE, ANCHOR_LAT, ANCHOR_LON, ANCHOR_TIME)

    /** Удаляет снятые ключи из снимка настроек. Возвращает число удалённых. */
    fun clean(prefs: MutablePreferences): Int {
        var removed = 0
        if (prefs.contains(stringPreferencesKey(ECO_PROFILE))) {
            prefs.remove(stringPreferencesKey(ECO_PROFILE)); removed++
        }
        if (prefs.contains(booleanPreferencesKey(ECO_MODE))) {
            prefs.remove(booleanPreferencesKey(ECO_MODE)); removed++
        }
        if (prefs.contains(doublePreferencesKey(ANCHOR_LAT))) {
            prefs.remove(doublePreferencesKey(ANCHOR_LAT)); removed++
        }
        if (prefs.contains(doublePreferencesKey(ANCHOR_LON))) {
            prefs.remove(doublePreferencesKey(ANCHOR_LON)); removed++
        }
        if (prefs.contains(longPreferencesKey(ANCHOR_TIME))) {
            prefs.remove(longPreferencesKey(ANCHOR_TIME)); removed++
        }
        return removed
    }
}
