package com.ezhou.rotblock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Calm greens for "allowed", a single warm red reserved for the escape hatches. */
private val Ink = Color(0xFF101014)
private val InkRaised = Color(0xFF17171D)
private val Allow = Color(0xFF7FD1A6)
private val AllowInk = Color(0xFF06281B)
private val Alarm = Color(0xFFE5484D)
private val Muted = Color(0xFF8A909A)

private val Dark = darkColorScheme(
    primary = Allow,
    onPrimary = AllowInk,
    primaryContainer = Color(0xFF17372A),
    onPrimaryContainer = Allow,
    secondary = Color(0xFF9BB4D6),
    onSecondary = Color(0xFF0B1A2B),
    error = Alarm,
    onError = Color.White,
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFB4B6),
    background = Ink,
    onBackground = Color(0xFFECEEF1),
    surface = Ink,
    onSurface = Color(0xFFECEEF1),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2C2C35),
    outlineVariant = Color(0xFF22222A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1E7A55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEDC),
    onPrimaryContainer = Color(0xFF06281B),
    error = Color(0xFFC02328),
    background = Color(0xFFFAFAFB),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F1F3),
    onSurfaceVariant = Color(0xFF5C6069),
)

@Composable
fun RotblockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
