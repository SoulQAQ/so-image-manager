package cn.soul2.imageai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import cn.soul2.imageai.ui.settings.ThemeMode
import cn.soul2.imageai.ui.settings.themeSettings
import androidx.compose.ui.platform.LocalContext
import cn.soul2.imageai.ui.settings.themeSettings

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnBackground,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnBackground,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightSurfaceVariant,
    onErrorContainer = LightError,
    scrim = LightScrim
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnBackground,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnBackground,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkSurfaceVariant,
    onErrorContainer = DarkError,
    scrim = DarkScrim
)

@Composable
fun ImageAITheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeMode by context.themeSettings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val isSystemDarkTheme = LocalConfiguration.current.uiMode.and(0x30) == 0x30

    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}