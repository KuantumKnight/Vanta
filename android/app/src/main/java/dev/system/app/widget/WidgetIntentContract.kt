package dev.system.app.widget

/** Stable MainActivity handoff contract for Glance entry points. */
object WidgetIntentContract {
    const val ACTION_QUICK_ADD = "dev.system.app.action.QUICK_ADD"
    const val EXTRA_QUICK_ADD_SOURCE = "dev.system.app.extra.QUICK_ADD_SOURCE"
    const val EXTRA_QUICK_ADD_DATE = "dev.system.app.extra.QUICK_ADD_DATE"

    const val SOURCE_NOW_NEXT = "now_next_widget"
    const val SOURCE_TODAY_COMPACT = "today_compact_widget"
}
