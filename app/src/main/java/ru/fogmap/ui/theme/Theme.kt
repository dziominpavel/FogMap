package ru.fogmap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import ru.fogmap.data.ThemeModes

/**
 * Оболочка темы (remove-system-theme): dark/light, default dark.
 * Переключение — рекомпозицией NavHost, без recreate Activity.
 */
@Composable
fun FogMapTheme(
    themeMode: String = ThemeModes.DEFAULT,
    content: @Composable () -> Unit
) {
    val dark = themeMode != ThemeModes.LIGHT
    MaterialTheme(
        colorScheme = if (dark) FogDarkColors else FogLightColors,
        typography = FogMapTypography,
        shapes = FogMapShapes,
        content = content
    )
}

/**
 * Удобный флаг для мест вне композиции темы (карта): любое значение кроме
 * `light` (нормализация D1 гарантирует dark/light) означает тёмную тему.
 */
fun isDarkTheme(themeMode: String): Boolean = themeMode != ThemeModes.LIGHT
