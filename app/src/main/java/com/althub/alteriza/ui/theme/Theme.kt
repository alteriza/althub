package com.althub.alteriza.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BbBlack = Color(0xFF050505)
val BbBackground = Color(0xFF0A0A0A)
val BbSurface = Color(0xFF121212)
val BbSurfaceElevated = Color(0xFF1A1A1A)
val BbBorder = Color(0xFF262626)
val BbBorderBright = Color(0xFF3D3D3D)
val BbWhite = Color(0xFFF5F5F5)
val BbMuted = Color(0xFF8C8C8C)
val BbOrange = Color(0xFFFF6B00)

private val AltHubColorScheme = darkColorScheme(
    primary = BbWhite,
    onPrimary = BbBlack,
    secondary = BbOrange,
    onSecondary = BbBlack,
    background = BbBackground,
    onBackground = BbWhite,
    surface = BbSurface,
    onSurface = BbWhite,
    surfaceVariant = BbSurfaceElevated,
    onSurfaceVariant = BbMuted,
    outline = BbBorder
)

@Composable
fun AltHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AltHubColorScheme,
        content = content
    )
}