package dev.system.app.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Small, app-private persisted projection read by Glance without touching Room. */
internal object WidgetDayStateStore {
    private const val PREFERENCES = "vanta_widget_day_state"
    private const val STATE_KEY = "state_json"

    fun read(context: Context): WidgetDayState? {
        val encoded = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(STATE_KEY, null) ?: return null
        return decode(encoded)
    }

    fun write(context: Context, state: WidgetDayState) {
        check(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(STATE_KEY, encode(state))
                .commit(),
        ) { "Could not persist the Vanta widget day state." }
    }

    fun markCompleted(context: Context, occurrenceId: String) {
        val state = read(context) ?: return
        val todayItem = state.todayItems.firstOrNull { it.id == occurrenceId }
        val matchesCurrent = state.currentOccurrence?.id == occurrenceId
        val matchesNext = state.nextOccurrence?.id == occurrenceId
        if (todayItem == null && !matchesCurrent && !matchesNext) return
        val updatedItems = state.todayItems.map { item ->
            if (item.id == occurrenceId) item.copy(completed = true) else item
        }
        val newlyCompletedToday = todayItem != null && !todayItem.completed
        write(
            context,
            state.copy(
                currentOccurrence = state.currentOccurrence?.takeUnless { it.id == occurrenceId },
                nextOccurrence = state.nextOccurrence?.takeUnless { it.id == occurrenceId },
                todayItems = updatedItems,
                completedCount = (state.completedCount + (if (newlyCompletedToday) 1 else 0))
                    .coerceAtMost(state.totalCount),
            ),
        )
    }

    internal fun encode(state: WidgetDayState): String = JSONObject()
        .put("generatedAtEpochMillis", state.generatedAtEpochMillis)
        .put("localDate", state.localDate)
        .put("currentOccurrence", state.currentOccurrence?.let(::encodeItem))
        .put("nextOccurrence", state.nextOccurrence?.let(::encodeItem))
        .put("todayItems", JSONArray().apply { state.todayItems.forEach { put(encodeItem(it)) } })
        .put("plannedMinutes", state.plannedMinutes)
        .put("completedCount", state.completedCount)
        .put("totalCount", state.totalCount)
        .put("nextBoundaryEpochMillis", state.nextBoundaryEpochMillis)
        .put("syncWarning", state.syncWarning)
        .toString()

    internal fun decode(encoded: String): WidgetDayState? = try {
        val json = JSONObject(encoded)
        val itemsJson = json.optJSONArray("todayItems") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                decodeItem(itemsJson.optJSONObject(index))?.let(::add)
            }
        }
        WidgetDayState(
            generatedAtEpochMillis = json.getLong("generatedAtEpochMillis"),
            localDate = json.getString("localDate"),
            currentOccurrence = decodeItem(json.optJSONObject("currentOccurrence")),
            nextOccurrence = decodeItem(json.optJSONObject("nextOccurrence")),
            todayItems = items,
            plannedMinutes = json.optInt("plannedMinutes", 0),
            completedCount = json.optInt("completedCount", 0),
            totalCount = json.optInt("totalCount", items.size),
            nextBoundaryEpochMillis = json.getLong("nextBoundaryEpochMillis"),
            syncWarning = json.optBoolean("syncWarning", false),
        )
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun encodeItem(item: WidgetDayItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("title", item.title)
        .put("scheduledFor", item.scheduledFor)
        .put("durationMinutes", item.durationMinutes)
        .put("allDay", item.allDay)
        .put("completed", item.completed)

    private fun decodeItem(json: JSONObject?): WidgetDayItem? = json?.let {
        runCatching {
            WidgetDayItem(
                id = it.getString("id"),
                title = it.getString("title"),
                scheduledFor = it.optString("scheduledFor").takeUnless(String::isBlank),
                durationMinutes = if (it.isNull("durationMinutes")) null else it.getInt("durationMinutes"),
                allDay = it.optBoolean("allDay", false),
                completed = it.optBoolean("completed", false),
            )
        }.getOrNull()
    }
}
