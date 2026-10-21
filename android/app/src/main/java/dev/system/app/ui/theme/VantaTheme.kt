package dev.system.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import dev.system.app.settings.ThemePreference

private val VantaLightColors = lightColorScheme(
    primary = Color(0xFF006B61),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFD9),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4C635F),
    onSecondary = Color.White,
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF9FBF9),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE5ECE9),
    onSurfaceVariant = Color(0xFF404845),
    outline = Color(0xFF707975),
    error = Color(0xFFBA1A1A),
)

private val VantaDarkColors = darkColorScheme(
    primary = Color(0xFF80D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA0F2E5),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    background = Color(0xFF101614),
    onBackground = Color(0xFFE0E5E2),
    surface = Color(0xFF151C19),
    onSurface = Color(0xFFE0E5E2),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFC0C9C4),
    outline = Color(0xFF89938E),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun VantaTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colors = if (dark) VantaDarkColors else VantaLightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
        window.statusBarColor = colors.background.toArgb()
        window.navigationBarColor = colors.background.toArgb()
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
