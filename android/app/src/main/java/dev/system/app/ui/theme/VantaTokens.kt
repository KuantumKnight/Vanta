package dev.system.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared spacing rhythm. Prefer these values for new and substantially edited UI. */
internal object VantaSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 40.dp
}

/** Shape family used by fields, cards, sheets, and controls. */
internal object VantaCorner {
    val tight: Dp = 4.dp
    val control: Dp = 8.dp
    val card: Dp = 16.dp
    val sheet: Dp = 28.dp
}

internal object VantaElevation {
    val flat: Dp = 0.dp
    val card: Dp = 1.dp
    val raised: Dp = 3.dp
    val modal: Dp = 8.dp
}

internal object VantaIconSize {
    val small: Dp = 16.dp
    val standard: Dp = 20.dp
    val large: Dp = 24.dp
    val touchTarget: Dp = 48.dp
}

internal object VantaLayout {
    val screenHorizontalPadding: Dp = 20.dp
    val compactScreenHorizontalPadding: Dp = 16.dp
    val contentMaxWidth: Dp = 720.dp
    val formMaxWidth: Dp = 560.dp
}

internal object VantaTimeline {
    val timeColumnWidth: Dp = 56.dp
    val railWidth: Dp = 20.dp
    val markerSize: Dp = 8.dp
    val compactBlockMinHeight: Dp = 44.dp
    val blockMinHeight: Dp = 56.dp
    val itemSpacing: Dp = 8.dp
}

/** Durations are short by default; callers pass them through vantaMotionDuration. */
internal object VantaMotion {
    const val immediateMillis: Int = 0
    const val fastMillis: Int = 120
    const val standardMillis: Int = 200
    const val emphasizedMillis: Int = 280
}

internal object VantaTypeSize {
    val heroTime = 48.sp
    val screenTitle = 28.sp
    val sectionTitle = 18.sp
    val taskTitle = 16.sp
    val body = 16.sp
    val metadata = 14.sp
    val caption = 12.sp
    val statistic = 28.sp
}

@Immutable
internal data class VantaSemanticColors(
    val surfaceRaised: Color,
    val surfaceSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val timelineHard: Color,
    val onTimelineHard: Color,
    val timelineSoft: Color,
    val onTimelineSoft: Color,
    val timelineFlexible: Color,
    val onTimelineFlexible: Color,
    val calendarExternal: Color,
    val onCalendarExternal: Color,
    val habit: Color,
    val focus: Color,
)

internal val LocalVantaColors = staticCompositionLocalOf {
    VantaSemanticColors(
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
}

internal val LocalVantaReducedMotion = staticCompositionLocalOf { false }

/** Returns zero duration when Android's animator-duration scale disables animation. */
@Composable
internal fun vantaMotionDuration(durationMillis: Int): Int =
    if (LocalVantaReducedMotion.current) VantaMotion.immediateMillis else durationMillis
