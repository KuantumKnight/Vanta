package dev.system.app.focus

import android.content.Context
import android.util.Base64
import dev.system.app.data.CachedOccurrence
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Small persistence seam keeps the lifecycle rules independently testable. */
internal interface FocusSessionPersistence {
    fun read(userId: String): FocusSession?
    fun write(session: FocusSession)
}

/**
 * Stores the latest focus session per user in its own SharedPreferences file. Keys are hashes
 * of user ids so account identifiers are not written into preference keys or values.
 */
class FocusSessionStore internal constructor(
    private val persistence: FocusSessionPersistence,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(SharedPreferencesFocusSessionPersistence(context.applicationContext))

    /** Returns the active session for this account, including one restored after process death. */
    fun activeSession(userId: String): FocusSession? {
        require(userId.isNotBlank()) { "A user id is required to load a focus session." }
        return persistence.read(userId)?.takeIf { it.status == FocusSessionStatus.ACTIVE }
    }

    /** Returns the latest session for this account, including a finished session if present. */
    fun latestSession(userId: String): FocusSession? {
        require(userId.isNotBlank()) { "A user id is required to load a focus session." }
        return persistence.read(userId)
    }

    /**
     * Rewrites a session's task reference after an offline-created occurrence receives its
     * canonical server id. The account key is supplied explicitly so reconciliation cannot
     * move another user's focus session.
     */
    fun reconcileOccurrenceId(userId: String, oldOccurrenceId: String, newOccurrenceId: String): Boolean {
        require(userId.isNotBlank()) { "A user id is required to reconcile a focus session." }
        require(oldOccurrenceId.isNotBlank() && newOccurrenceId.isNotBlank()) {
            "Occurrence ids are required to reconcile a focus session."
        }
        if (oldOccurrenceId == newOccurrenceId) return false
        return synchronized(FOCUS_SESSION_MUTATION_LOCK) {
            val session = persistence.read(userId) ?: return@synchronized false
            if (session.userId != userId || session.occurrenceId != oldOccurrenceId) return@synchronized false
            persistence.write(session.copy(occurrenceId = newOccurrenceId))
            true
        }
    }

    /** Starts one active session for the user. A prior finished session may be replaced. */
    fun start(
        userId: String,
        occurrence: CachedOccurrence,
        plannedDurationSeconds: Long = FocusSession.plannedDurationSeconds(occurrence),
        startedAtEpochMillis: Long = now(),
    ): FocusSession {
        require(userId.isNotBlank()) { "A user id is required to start a focus session." }
        require(occurrence.userId.isBlank() || occurrence.userId == userId) {
            "The occurrence belongs to a different user."
        }
        return synchronized(FOCUS_SESSION_MUTATION_LOCK) {
            check(activeSession(userId) == null) { "This user already has an active focus session." }
            FocusSession(
                userId = userId,
                occurrenceId = occurrence.id,
                occurrenceTitle = occurrence.title,
                plannedDurationSeconds = plannedDurationSeconds,
                startedAtEpochMillis = startedAtEpochMillis,
            ).also(persistence::write)
        }
    }

    /** Finishes the session as completed. The caller must still use the normal task completion path. */
    fun complete(userId: String, finishedAtEpochMillis: Long = now()): FocusSession = finish(
        userId = userId,
        transform = { it.complete(finishedAtEpochMillis) },
    )

    /** Finishes the session early without changing the occurrence's task status. */
    fun endEarly(userId: String, finishedAtEpochMillis: Long = now()): FocusSession = finish(
        userId = userId,
        transform = { it.endEarly(finishedAtEpochMillis) },
    )

    private fun finish(userId: String, transform: (FocusSession) -> FocusSession): FocusSession {
        require(userId.isNotBlank()) { "A user id is required to finish a focus session." }
        return synchronized(FOCUS_SESSION_MUTATION_LOCK) {
            val session = activeSession(userId) ?: error("There is no active focus session for this user.")
            transform(session).also(persistence::write)
        }
    }
}

private val FOCUS_SESSION_MUTATION_LOCK = Any()

private class SharedPreferencesFocusSessionPersistence(context: Context) : FocusSessionPersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(userId: String): FocusSession? {
        val key = keyFor(userId)
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching { decode(userId, encoded) }
            .getOrElse {
                preferences.edit().remove(key).apply()
                null
            }
    }

    @Synchronized
    override fun write(session: FocusSession) {
        check(preferences.edit().putString(keyFor(session.userId), encode(session)).commit()) {
            "Could not persist the focus session."
        }
    }

    private fun keyFor(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(StandardCharsets.UTF_8))
        return "$SESSION_KEY_PREFIX${Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"
    }

    private fun encode(session: FocusSession): String {
        val occurrence = encodeText(session.occurrenceId)
        val title = encodeText(session.occurrenceTitle)
        return listOf(
            FORMAT_VERSION,
            session.status.name,
            session.startedAtEpochMillis.toString(),
            session.plannedDurationSeconds.toString(),
            session.finishedAtEpochMillis?.toString() ?: EMPTY_FINISH_TIME,
            occurrence,
            title,
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun decode(userId: String, encoded: String): FocusSession {
        val fields = encoded.split(FIELD_SEPARATOR)
        require(fields.size == FIELD_COUNT && fields[0] == FORMAT_VERSION) { "Unsupported focus session format." }
        return FocusSession(
            userId = userId,
            occurrenceId = decodeText(fields[5]),
            occurrenceTitle = decodeText(fields[6]),
            plannedDurationSeconds = fields[3].toLong(),
            startedAtEpochMillis = fields[2].toLong(),
            status = FocusSessionStatus.valueOf(fields[1]),
            finishedAtEpochMillis = fields[4].takeUnless { it == EMPTY_FINISH_TIME }?.toLong(),
        )
    }

    private fun encodeText(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decodeText(value: String): String = String(
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        StandardCharsets.UTF_8,
    )

    private companion object {
        const val PREFERENCES_NAME = "vanta_focus_sessions_v1"
        const val SESSION_KEY_PREFIX = "session_"
        const val FORMAT_VERSION = "1"
        const val FIELD_SEPARATOR = "|"
        const val EMPTY_FINISH_TIME = "-"
        const val FIELD_COUNT = 7
    }
}
