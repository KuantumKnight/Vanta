package dev.system.app.settings

import java.time.DayOfWeek

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}
/** App behavior preferences only. Authentication and account identity stay in their existing stores. */
data class UserPreferences(
    val dayStartMinutes: Int = DEFAULT_DAY_START_MINUTES,
    val dayEndMinutes: Int = DEFAULT_DAY_END_MINUTES,
    val defaultDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val snapIntervalMinutes: Int = DEFAULT_SNAP_INTERVAL_MINUTES,
    val weekStartDay: DayOfWeek = DayOfWeek.MONDAY,
    val morningBriefingEnabled: Boolean = false,
    val morningBriefingMinute: Int = DEFAULT_MORNING_BRIEFING_MINUTE,
    val eveningReviewEnabled: Boolean = false,
    val eveningReviewMinute: Int = DEFAULT_EVENING_REVIEW_MINUTE,
    val taskRemindersEnabled: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val focusCompletionNotificationEnabled: Boolean = true,
    val showPlannedVsActual: Boolean = false,
    val keepScreenAwake: Boolean = false,
) {
    /** Replaces invalid persisted or caller-supplied values with the documented defaults. */
    fun normalized(): UserPreferences {
        val dayRangeValid = dayStartMinutes in MINUTE_OF_DAY_RANGE &&
            dayEndMinutes in MINUTE_OF_DAY_RANGE &&
            dayEndMinutes > dayStartMinutes
        return copy(
            dayStartMinutes = if (dayRangeValid) dayStartMinutes else DEFAULT_DAY_START_MINUTES,
            dayEndMinutes = if (dayRangeValid) dayEndMinutes else DEFAULT_DAY_END_MINUTES,
            defaultDurationMinutes = defaultDurationMinutes.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
                ?: DEFAULT_DURATION_MINUTES,
            snapIntervalMinutes = snapIntervalMinutes.takeIf { it in SUPPORTED_SNAP_INTERVALS }
                ?: DEFAULT_SNAP_INTERVAL_MINUTES,
            weekStartDay = DayOfWeek.values().firstOrNull { it == weekStartDay } ?: DayOfWeek.MONDAY,
            morningBriefingMinute = morningBriefingMinute.takeIf { it in MINUTE_OF_DAY_RANGE }
                ?: DEFAULT_MORNING_BRIEFING_MINUTE,
            eveningReviewMinute = eveningReviewMinute.takeIf { it in MINUTE_OF_DAY_RANGE }
                ?: DEFAULT_EVENING_REVIEW_MINUTE,
        )
    }

    companion object {
        const val DEFAULT_DAY_START_MINUTES = 7 * 60
        const val DEFAULT_DAY_END_MINUTES = 23 * 60
        const val DEFAULT_DURATION_MINUTES = 30
        const val DEFAULT_SNAP_INTERVAL_MINUTES = 15
        const val DEFAULT_MORNING_BRIEFING_MINUTE = 8 * 60
        const val DEFAULT_EVENING_REVIEW_MINUTE = 21 * 60
        const val MIN_TASK_DURATION_MINUTES = 5
        const val MAX_TASK_DURATION_MINUTES = 1440
        val MINUTE_OF_DAY_RANGE = 0..1439
        val SUPPORTED_SNAP_INTERVALS = setOf(5, 10, 15, 20, 30, 60)
    }
}
