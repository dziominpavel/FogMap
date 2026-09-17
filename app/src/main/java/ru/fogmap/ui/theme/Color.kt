package ru.fogmap.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Фиксированные токены FogMap (ui-dark-redesign, решение в design.md):
 * Dynamic Color отклонен — ломает игровой туман и контраст вуали.
 */

// Общие акценты.
val FogMint = Color(0xFF6EE7B7)
val FogMistBlue = Color(0xFF93C5FD)
val FogLantern = Color(0xFFFBBF24)
val FogError = Color(0xFFF87171)

// Dark (default): фон #0F1419, карточки #1A212B.
val FogDarkBackground = Color(0xFF0F1419)
val FogDarkSurface = Color(0xFF1A212B)
val FogDarkOnPrimary = Color(0xFF052E1F)

// Light: бумага #F6F8F7, primary темнее для контраста.
val FogLightBackground = Color(0xFFF6F8F7)
val FogLightPrimary = Color(0xFF059669)

val FogDarkColors = darkColorScheme(
    primary = FogMint,
    onPrimary = FogDarkOnPrimary,
    primaryContainer = Color(0xFF0B3B2C),
    onPrimaryContainer = FogMint,
    secondary = FogMistBlue,
    onSecondary = Color(0xFF0B1B33),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = FogMistBlue,
    tertiary = FogLantern,
    background = FogDarkBackground,
    onBackground = Color(0xFFE8EEF2),
    surface = FogDarkSurface,
    onSurface = Color(0xFFE8EEF2),
    surfaceVariant = Color(0xFF232D3A),
    onSurfaceVariant = Color(0xFFB9C6D2),
    outline = Color(0xFF3A4656),
    error = FogError,
    onError = Color(0xFF3B0A0A)
)

val FogLightColors = lightColorScheme(
    primary = FogLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F3D0),
    onPrimaryContainer = Color(0xFF052E1F),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFDBFE),
    onSecondaryContainer = Color(0xFF0B1B33),
    tertiary = Color(0xFFB45309),
    background = FogLightBackground,
    onBackground = Color(0xFF10181F),
    surface = Color.White,
    onSurface = Color(0xFF10181F),
    surfaceVariant = Color(0xFFE6EDE9),
    onSurfaceVariant = Color(0xFF3D4A44),
    outline = Color(0xFFB9C6BE),
    error = Color(0xFFB3261E),
    onError = Color.White
)
