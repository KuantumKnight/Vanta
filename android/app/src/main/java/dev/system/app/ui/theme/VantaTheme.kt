package dev.system.app.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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

private val VantaLightSemanticColors = VantaSemanticColors(
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFEEF3F1),
    textPrimary = Color(0xFF171D1B),
    textSecondary = Color(0xFF404845),
    textMuted = Color(0xFF59645F),
    accent = Color(0xFF006B61),
    successContainer = Color(0xFFE1F2E5),
    onSuccessContainer = Color(0xFF235A36),
    warningContainer = Color(0xFFF6E8C8),
    onWarningContainer = Color(0xFF654D16),
    dangerContainer = Color(0xFFF9DEDC),
    onDangerContainer = Color(0xFF8C1D18),
    timelineHard = Color(0xFFF4E4DE),
    onTimelineHard = Color(0xFF6F3424),
    timelineSoft = Color(0xFFDDF1EC),
    onTimelineSoft = Color(0xFF00574D),
    timelineFlexible = Color(0xFFEAEBDD),
    onTimelineFlexible = Color(0xFF51542E),
    calendarExternal = Color(0xFFE5ECF7),
    onCalendarExternal = Color(0xFF344F78),
    habit = Color(0xFF4F665F),
    focus = Color(0xFF006B61),
)

private val VantaDarkSemanticColors = VantaSemanticColors(
    surfaceRaised = Color(0xFF202825),
    surfaceSubtle = Color(0xFF1A211E),
    textPrimary = Color(0xFFE0E5E2),
    textSecondary = Color(0xFFC0C9C4),
    textMuted = Color(0xFFA7B1AC),
    accent = Color(0xFF80D5C9),
    successContainer = Color(0xFF1C3B2A),
    onSuccessContainer = Color(0xFFA4D8AF),
    warningContainer = Color(0xFF3F351C),
    onWarningContainer = Color(0xFFE5CC88),
    dangerContainer = Color(0xFF4A2524),
    onDangerContainer = Color(0xFFF3B6B2),
    timelineHard = Color(0xFF422D28),
    onTimelineHard = Color(0xFFF1B8A5),
    timelineSoft = Color(0xFF194038),
    onTimelineSoft = Color(0xFFA0DCD1),
    timelineFlexible = Color(0xFF34362A),
    onTimelineFlexible = Color(0xFFD4D6AC),
    calendarExternal = Color(0xFF28364A),
    onCalendarExternal = Color(0xFFBCD0F2),
    habit = Color(0xFFB1CCC6),
    focus = Color(0xFF80D5C9),
)

private val VantaTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Medium),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall,
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium,
        labelSmall = base.labelSmall,
    )
}

private val VantaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(VantaCorner.tight),
    small = androidx.compose.foundation.shape.RoundedCornerShape(VantaCorner.control),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(VantaCorner.card),
    large = androidx.compose.foundation.shape.RoundedCornerShape(VantaCorner.sheet),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(VantaCorner.sheet),
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
    val semanticColors = if (dark) VantaDarkSemanticColors else VantaLightSemanticColors
    val reducedMotion = rememberSystemReducedMotion()
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
    CompositionLocalProvider(
        LocalVantaColors provides semanticColors,
        LocalVantaReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = VantaTypography,
            shapes = VantaShapes,
            content = content,
        )
    }
}

@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current.applicationContext
    var reducedMotion by remember(context) {
        mutableStateOf(readAnimatorScaleDisabled(context))
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = readAnimatorScaleDisabled(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reducedMotion
}

private fun readAnimatorScaleDisabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
