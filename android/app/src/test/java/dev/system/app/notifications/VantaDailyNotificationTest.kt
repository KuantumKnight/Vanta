package dev.system.app.notifications

import dev.system.app.ui.navigation.DashboardTab
import dev.system.app.settings.UserPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VantaDailyNotificationTest {
    @Test
    fun schedulesTheConfiguredLocalTimeAndAdvancesWhenItHasPassed() {
        val zone = ZoneId.of("Asia/Kolkata")
        val before = ZonedDateTime.of(2026, 10, 21, 7, 30, 0, 0, zone)
        val after = ZonedDateTime.of(2026, 10, 21, 8, 30, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, zone),
            nextDailyNotificationTrigger(before, 8 * 60),
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 22, 8, 0, 0, 0, zone),
            nextDailyNotificationTrigger(after, 8 * 60),
        )
    }

    @Test
    fun timezoneAndDaylightSavingRulesAreAppliedToWallClockTime() {
        val newYork = ZoneId.of("America/New_York")
        val beforeDstGap = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, newYork)
        val afterGap = nextDailyNotificationTrigger(beforeDstGap, 2 * 60 + 30)

        // The nonexistent 02:30 wall time resolves to 03:30 on the spring-forward date.
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, newYork), afterGap)
        val sameInstantInKolkata = ZonedDateTime.ofInstant(beforeDstGap.toInstant(), ZoneId.of("Asia/Kolkata"))
        assertNotEquals(
            nextDailyNotificationTrigger(beforeDstGap, 8 * 60).toInstant(),
            nextDailyNotificationTrigger(sameInstantInKolkata, 8 * 60).toInstant(),
        )
    }

    @Test
    fun notificationActionsOpenTheirIntendedDashboardAndIgnoreUnknownActions() {
        assertEquals(DashboardTab.Today, VantaNotificationNavigation.destination(VantaNotificationNavigation.ACTION_OPEN_TODAY))
        assertEquals(DashboardTab.EveningReview, VantaNotificationNavigation.destination(VantaNotificationNavigation.ACTION_OPEN_EVENING_REVIEW))
        assertEquals(null, VantaNotificationNavigation.destination("other"))
    }

    @Test
    fun dailyNotificationsAreOptInAndUseDistinctUniqueWorkNames() {
        val defaults = UserPreferences()
        val enabled = defaults.copy(morningBriefingEnabled = true, eveningReviewEnabled = true)

        assertEquals(false, VantaDailyNotificationScheduler.isEnabled(DailyNotificationKind.MORNING, defaults))
        assertEquals(false, VantaDailyNotificationScheduler.isEnabled(DailyNotificationKind.EVENING, defaults))
        assertEquals(true, VantaDailyNotificationScheduler.isEnabled(DailyNotificationKind.MORNING, enabled))
        assertEquals(true, VantaDailyNotificationScheduler.isEnabled(DailyNotificationKind.EVENING, enabled))
        assertEquals(2, DailyNotificationKind.values().map { it.workName }.distinct().size)
    }

    @Test
    fun scheduledTriggerIsAlwaysStrictlyAfterNowAndRejectsInvalidMinute() {
        val now = ZonedDateTime.ofInstant(Instant.parse("2026-10-21T02:30:00Z"), ZoneId.of("Asia/Kolkata"))
        val next = nextDailyNotificationTrigger(now, 8 * 60)

        assertTrue(next.isAfter(now))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            nextDailyNotificationTrigger(now, 1440)
        }
    }
}
