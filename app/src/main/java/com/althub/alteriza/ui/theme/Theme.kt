package com.althub.alteriza.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val BbBackground = Color(0xFF0D0D0D)
val BbSurface = Color(0xFF141414)
val BbSurfaceElevated = Color(0xFF1C1C1C)
val BbBorder = Color(0xFF2B2B2B)
val BbBorderBright = Color(0xFF4A4A4A)
val BbWhite = Color(0xFFFFFFFF)
val BbBlack = Color(0xFF050505)
val BbMuted = Color(0xFF8C8C8C)
val BbOrange = Color(0xFFFF6B00)

private val BlackboxColorScheme = darkColorScheme(
    primary = BbWhite,
    onPrimary = BbBlack,
    secondary = BbOrange,
    onSecondary = BbBlack,
    background = BbBackground,
    onBackground = BbWhite,
    surface = BbSurface,
    onSurface = BbWhite,
    outline = BbBorder
)

private val CubicShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun AltHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackboxColorScheme,
        shapes = CubicShapes,
        content = content
    )
}
