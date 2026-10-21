package dev.system.app.ui.today.timeline

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToLong

internal const val DEFAULT_TIME_SLOT_MINUTES = 15

/**
 * Snaps an instant to the closest local wall-clock slot. Ties round forward, and the
 * conversion back through the zone handles crossing midnight (and daylight-saving gaps).
 */
internal fun snapToTimeSlot(
    instant: Instant,
    intervalMinutes: Int = DEFAULT_TIME_SLOT_MINUTES,
    zone: ZoneId = VANTA_TIME_ZONE,
): Instant {
    require(intervalMinutes in 1..(24 * 60)) { "Snap interval must be between one minute and one day." }

    val local = instant.atZone(zone)
    val intervalSeconds = intervalMinutes * 60L
    val localSeconds = local.toLocalTime().toSecondOfDay().toDouble() + local.nano / 1_000_000_000.0
    val snappedSeconds = floor((localSeconds + intervalSeconds / 2.0) / intervalSeconds).toLong() * intervalSeconds
    val snappedLocal = local.toLocalDate().atStartOfDay().plusSeconds(snappedSeconds)
    return snappedLocal.atZone(zone).toInstant()
}

/**
 * Converts the vertical drag distance into a local time preview. One [dpPerInterval]
 * of movement represents one scheduling slot; no persistent work happens here.
 */
internal fun previewTimeForVerticalDrag(
    original: Instant,
    verticalDeltaDp: Float,
    intervalMinutes: Int = DEFAULT_TIME_SLOT_MINUTES,
    dpPerInterval: Float = DEFAULT_DRAG_DP_PER_INTERVAL,
    zone: ZoneId = VANTA_TIME_ZONE,
): Instant {
    require(dpPerInterval > 0f && dpPerInterval.isFinite()) { "Drag scale must be a positive finite value." }
    require(intervalMinutes in 1..(24 * 60)) { "Snap interval must be between one minute and one day." }
    require(verticalDeltaDp.isFinite()) { "Drag distance must be a finite value." }
    val movedSlots = verticalDeltaDp / dpPerInterval
    val movedSeconds = (movedSlots * intervalMinutes * 60.0).roundToLong()
    return snapToTimeSlot(original.plusSeconds(movedSeconds), intervalMinutes, zone)
}

internal const val DEFAULT_DRAG_DP_PER_INTERVAL = 48f
