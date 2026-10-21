package dev.system.app.notifications

import dev.system.app.ui.navigation.DashboardTab

/** Explicit in-app destinations used by lock-screen-safe Vanta notifications. */
internal object VantaNotificationNavigation {
    const val ACTION_OPEN_TODAY = "dev.system.app.action.OPEN_TODAY"
    const val ACTION_OPEN_EVENING_REVIEW = "dev.system.app.action.OPEN_EVENING_REVIEW"

    fun destination(action: String?): DashboardTab? = when (action) {
        ACTION_OPEN_TODAY -> DashboardTab.Today
        ACTION_OPEN_EVENING_REVIEW -> DashboardTab.EveningReview
        else -> null
    }
}
