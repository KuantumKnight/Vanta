package dev.system.app.ui.today.timeline

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeSlotTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun snapsToNearestQuarterHour() {
        assertEquals(at("2026-10-21T14:00:00"), snap("2026-10-21T14:03:00"))
        assertEquals(at("2026-10-21T14:15:00"), snap("2026-10-21T14:22:00"))
        assertEquals(at("2026-10-21T14:45:00"), snap("2026-10-21T14:38:00"))
    }

    @Test
    fun roundsHalfWayForwardAndCrossesHourBoundary() {
        assertEquals(at("2026-10-21T15:00:00"), snap("2026-10-21T14:52:30"))
        assertEquals(at("2026-10-21T15:00:00"), snap("2026-10-21T14:53:00"))
    }

    @Test
    fun carriesRoundingAcrossMidnight() {
        assertEquals(at("2026-10-22T00:00:00"), snap("2026-10-21T23:58:00"))
    }

    @Test
    fun verticalDragMapsOneSlotAndSnaps() {
        assertEquals(
            at("2026-10-21T14:45:00"),
            previewTimeForVerticalDrag(at("2026-10-21T15:03:00"), -DEFAULT_DRAG_DP_PER_INTERVAL, zone = zone),
        )
        assertEquals(
            at("2026-10-21T15:15:00"),
            previewTimeForVerticalDrag(at("2026-10-21T15:03:00"), DEFAULT_DRAG_DP_PER_INTERVAL, zone = zone),
        )
    }

    @Test
    fun acceptsConfiguredIntervalAndRejectsInvalidValues() {
        assertEquals(at("2026-10-21T14:10:00"), snap("2026-10-21T14:09:00", intervalMinutes = 10))
        assertThrows(IllegalArgumentException::class.java) { snap("2026-10-21T14:09:00", intervalMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            previewTimeForVerticalDrag(at("2026-10-21T14:00:00"), 20f, dpPerInterval = 0f)
        }
    }

    private fun snap(value: String, intervalMinutes: Int = DEFAULT_TIME_SLOT_MINUTES): Instant =
        snapToTimeSlot(at(value), intervalMinutes, zone)

    private fun at(value: String): Instant = LocalDateTime.parse(value).atZone(zone).toInstant()
}
