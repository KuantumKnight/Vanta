package dev.system.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek

class UserPreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsMatchExistingPlanningBehavior() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            dataFile("defaults.preferences_pb")
        }
        try {
            assertEquals(UserPreferences(), DataStoreUserPreferencesRepository(store).preferences.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preferencesPersistAcrossRepositoryInstances() = runBlocking {
        val file = dataFile("persisted.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val expected = UserPreferences(
            dayStartMinutes = 6 * 60 + 30,
            dayEndMinutes = 22 * 60,
            defaultDurationMinutes = 45,
            snapIntervalMinutes = 10,
            weekStartDay = DayOfWeek.SUNDAY,
            morningBriefingEnabled = true,
            morningBriefingMinute = 7 * 60 + 15,
            eveningReviewEnabled = true,
            eveningReviewMinute = 20 * 60 + 45,
            taskRemindersEnabled = false,
            theme = ThemePreference.DARK,
            showPlannedVsActual = true,
        )
        DataStoreUserPreferencesRepository(firstStore).update { expected }
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        try {
            assertEquals(expected, DataStoreUserPreferencesRepository(secondStore).preferences.first())
        } finally {
            secondScope.cancel()
        }
    }

    @Test
    fun resetRestoresDefaultsByClearingTheDedicatedSettingsStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            dataFile("reset.preferences_pb")
        }
        val repository = DataStoreUserPreferencesRepository(store)
        val unrelated = intPreferencesKey("unrelated_future_value")
        try {
            store.edit { it[unrelated] = 99 }
            repository.update { it.copy(theme = ThemePreference.DARK, morningBriefingEnabled = true) }
            repository.reset()

            assertEquals(UserPreferences(), repository.preferences.first())
            assertEquals(null, store.data.first()[unrelated])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidStoredValuesFallBackToDefaults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            dataFile("invalid.preferences_pb")
        }
        try {
            store.edit { values ->
                values[intPreferencesKey("day_start_minutes")] = 22 * 60
                values[intPreferencesKey("day_end_minutes")] = 6 * 60
                values[intPreferencesKey("default_duration_minutes")] = 0
                values[intPreferencesKey("snap_interval_minutes")] = 13
                values[stringPreferencesKey("week_start_day")] = "NOT_A_DAY"
                values[intPreferencesKey("morning_briefing_minute")] = 1440
                values[stringPreferencesKey("theme")] = "ULTRA"
            }

            assertEquals(UserPreferences(), DataStoreUserPreferencesRepository(store).preferences.first())
        } finally {
            scope.cancel()
        }
    }

    private fun dataFile(name: String): File = File(temporaryFolder.root, name)
}
