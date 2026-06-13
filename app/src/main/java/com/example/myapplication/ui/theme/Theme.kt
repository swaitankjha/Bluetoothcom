package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.material.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
// Dark theme colors
val Shapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp)
)
private val DarkColorPalette = darkColors(
    primary = Purple80,
    secondary = PurpleGrey80,
    background = PurpleGrey40,
    surface = PurpleGrey80,
    onPrimary = Purple40,
    onSecondary = Purple40,
    onBackground = Purple40,
    onSurface = Purple40
)


private val LightColorPalette = lightColors(
    primary = Purple40,
    secondary = PurpleGrey40,
    background = Purple40,
    surface = PurpleGrey40,
    onPrimary = Purple80,
    onSecondary = Purple80,
    onBackground = Purple80,
    onSurface = Purple80
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
