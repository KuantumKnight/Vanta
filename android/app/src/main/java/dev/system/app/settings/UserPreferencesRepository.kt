package dev.system.app.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>

    suspend fun update(transform: (UserPreferences) -> UserPreferences)

    /** Resets Vanta behavior preferences only. It never touches account, task, or sync data. */
    suspend fun reset()
}

private val Context.vantaSettingsDataStore by preferencesDataStore(
    name = "vanta_user_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Typed, reactive persistence for non-secret Vanta settings. */
class DataStoreUserPreferencesRepository internal constructor(
    private val store: androidx.datastore.core.DataStore<Preferences>,
) : UserPreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.vantaSettingsDataStore)

    override val preferences: Flow<UserPreferences> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decode)
        .distinctUntilChanged()

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { mutable ->
            val updated = transform(decode(mutable)).normalized()
            mutable[DAY_START] = updated.dayStartMinutes
            mutable[DAY_END] = updated.dayEndMinutes
            mutable[DEFAULT_DURATION] = updated.defaultDurationMinutes
            mutable[SNAP_INTERVAL] = updated.snapIntervalMinutes
            mutable[WEEK_START] = updated.weekStartDay.name
            mutable[MORNING_ENABLED] = updated.morningBriefingEnabled
            mutable[MORNING_MINUTE] = updated.morningBriefingMinute
            mutable[EVENING_ENABLED] = updated.eveningReviewEnabled
            mutable[EVENING_MINUTE] = updated.eveningReviewMinute
            mutable[TASK_REMINDERS_ENABLED] = updated.taskRemindersEnabled
            mutable[THEME] = updated.theme.name
            mutable[FOCUS_COMPLETION_NOTIFICATION] = updated.focusCompletionNotificationEnabled
            mutable[SHOW_PLANNED_VS_ACTUAL] = updated.showPlannedVsActual
            mutable[KEEP_SCREEN_AWAKE] = updated.keepScreenAwake
        }
    }

    override suspend fun reset() {
        store.edit { it.clear() }
    }

    private fun decode(preferences: Preferences): UserPreferences {
        val defaults = UserPreferences()
        val dayStart = safeRead { preferences[DAY_START] } ?: defaults.dayStartMinutes
        val dayEnd = safeRead { preferences[DAY_END] } ?: defaults.dayEndMinutes
        val duration = safeRead { preferences[DEFAULT_DURATION] } ?: defaults.defaultDurationMinutes
        val snapInterval = safeRead { preferences[SNAP_INTERVAL] } ?: defaults.snapIntervalMinutes
        val weekStart = safeRead { preferences[WEEK_START] }
            ?.let { stored -> DayOfWeek.values().firstOrNull { it.name == stored } }
            ?: defaults.weekStartDay
        val theme = safeRead { preferences[THEME] }
            ?.let { stored -> ThemePreference.values().firstOrNull { it.name == stored } }
            ?: defaults.theme

        return UserPreferences(
            dayStartMinutes = dayStart,
            dayEndMinutes = dayEnd,
            defaultDurationMinutes = duration,
            snapIntervalMinutes = snapInterval,
            weekStartDay = weekStart,
            morningBriefingEnabled = safeRead { preferences[MORNING_ENABLED] } ?: defaults.morningBriefingEnabled,
            morningBriefingMinute = safeRead { preferences[MORNING_MINUTE] } ?: defaults.morningBriefingMinute,
            eveningReviewEnabled = safeRead { preferences[EVENING_ENABLED] } ?: defaults.eveningReviewEnabled,
            eveningReviewMinute = safeRead { preferences[EVENING_MINUTE] } ?: defaults.eveningReviewMinute,
            taskRemindersEnabled = safeRead { preferences[TASK_REMINDERS_ENABLED] } ?: defaults.taskRemindersEnabled,
            theme = theme,
            focusCompletionNotificationEnabled = safeRead { preferences[FOCUS_COMPLETION_NOTIFICATION] }
                ?: defaults.focusCompletionNotificationEnabled,
            showPlannedVsActual = safeRead { preferences[SHOW_PLANNED_VS_ACTUAL] } ?: defaults.showPlannedVsActual,
            keepScreenAwake = safeRead { preferences[KEEP_SCREEN_AWAKE] } ?: defaults.keepScreenAwake,
        ).normalized()
    }

    private inline fun <T> safeRead(read: () -> T): T? = runCatching(read).getOrNull()

    private companion object {
        val DAY_START = intPreferencesKey("day_start_minutes")
        val DAY_END = intPreferencesKey("day_end_minutes")
        val DEFAULT_DURATION = intPreferencesKey("default_duration_minutes")
        val SNAP_INTERVAL = intPreferencesKey("snap_interval_minutes")
        val WEEK_START = stringPreferencesKey("week_start_day")
        val MORNING_ENABLED = booleanPreferencesKey("morning_briefing_enabled")
        val MORNING_MINUTE = intPreferencesKey("morning_briefing_minute")
        val EVENING_ENABLED = booleanPreferencesKey("evening_review_enabled")
        val EVENING_MINUTE = intPreferencesKey("evening_review_minute")
        val TASK_REMINDERS_ENABLED = booleanPreferencesKey("task_reminders_enabled")
        val THEME = stringPreferencesKey("theme")
        val FOCUS_COMPLETION_NOTIFICATION = booleanPreferencesKey("focus_completion_notification_enabled")
        val SHOW_PLANNED_VS_ACTUAL = booleanPreferencesKey("show_planned_vs_actual")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
    }
}
