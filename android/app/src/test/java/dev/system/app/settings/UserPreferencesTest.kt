package dev.system.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    @Test
    fun normalizesInvalidDurationAndInterval() {
        val normalized = UserPreferences(defaultDurationMinutes = 1441, snapIntervalMinutes = 12).normalized()
        assertEquals(UserPreferences.DEFAULT_DURATION_MINUTES, normalized.defaultDurationMinutes)
        assertEquals(UserPreferences.DEFAULT_SNAP_INTERVAL_MINUTES, normalized.snapIntervalMinutes)
    }

    @Test
    fun invalidDayBoundsResetAsAConsistentPair() {
        val normalized = UserPreferences(dayStartMinutes = 23 * 60, dayEndMinutes = 4 * 60).normalized()
        assertEquals(UserPreferences.DEFAULT_DAY_START_MINUTES, normalized.dayStartMinutes)
        assertEquals(UserPreferences.DEFAULT_DAY_END_MINUTES, normalized.dayEndMinutes)
    }

    @Test
    fun invalidNotificationTimesUseDefaults() {
        val normalized = UserPreferences(morningBriefingMinute = -1, eveningReviewMinute = 1440).normalized()
        assertEquals(UserPreferences.DEFAULT_MORNING_BRIEFING_MINUTE, normalized.morningBriefingMinute)
        assertEquals(UserPreferences.DEFAULT_EVENING_REVIEW_MINUTE, normalized.eveningReviewMinute)
    }
}
