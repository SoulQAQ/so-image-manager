package com.soul2.imageai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PurpleGrey80,
    secondary = Secondary,
    onSecondary = OnSecondary,
    tertiary = Pink40,
    background = Background,
    surface = Surface,
    onBackground = OnBackground,
    onSurface = OnSurface
)

@Composable
fun ImageAITheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
