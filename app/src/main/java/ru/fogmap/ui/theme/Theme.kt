package ru.fogmap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import ru.fogmap.data.ThemeModes

/**
 * Оболочка темы (ui-dark-redesign 1.2): dark/light/system, default dark.
 * Переключение — рекомпозицией NavHost, без recreate Activity.
 */
@Composable
fun FogMapTheme(
    themeMode: String = ThemeModes.DEFAULT,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeModes.LIGHT -> false
        ThemeModes.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) FogDarkColors else FogLightColors,
        typography = FogMapTypography,
        shapes = FogMapShapes,
        content = content
    )
}

/** Удобный флаг для мест вне композиции темы (карта): system резолвится вызывающим. */
fun isDarkTheme(themeMode: String, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeModes.LIGHT -> false
    ThemeModes.DARK -> true
    else -> systemDark
}
