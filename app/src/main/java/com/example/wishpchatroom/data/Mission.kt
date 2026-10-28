package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

/**
 * The mission part of a room document. Expiry is not stored: it is the room's server-stamped
 * creation time plus [durationSeconds], which is exactly what firestore.rules computes too.
 */
data class Mission(
    val title: String = "",
    val goal: String = "",
    val announcement: String = "",
    val outcome: String = "",
    val durationSeconds: Long = 0
)

/** What the creator picks in the form. */
data class MissionSpec(val title: String, val goal: String, val durationSeconds: Long)

/** Allowed lifetimes. Must match firestore.rules. */
enum class MissionDuration(val seconds: Long, val label: String) {
    HALF_HOUR(1_800, "30 minutes"),
    SIX_HOURS(21_600, "6 hours"),
    ONE_DAY(86_400, "24 hours")
}

/** ACTIVE until the mission ends, READ_ONLY for the grace period, then GONE (the rules deny access). */
enum class MissionPhase { ACTIVE, READ_ONLY, GONE }

const val MISSION_GRACE_MS = 24L * 60 * 60 * 1000
const val MISSION_URGENT_MS = 10L * 60 * 1000

fun Room.expiresAtMillis(): Long? {
    val m = mission ?: return null
    val created = timestamp?.time ?: return null
    return created + m.durationSeconds * 1000
}

/** The phase at [nowMillis]. A room whose creation time is still pending counts as active. */
fun Room.missionPhase(nowMillis: Long): MissionPhase = phaseAt(expiresAtMillis(), nowMillis)

/** The phase for a known expiry time; null means the room has no mission, which never ends. */
fun phaseAt(expiresAtMillis: Long?, nowMillis: Long): MissionPhase {
    val expires = expiresAtMillis ?: return MissionPhase.ACTIVE
    return when {
        nowMillis < expires -> MissionPhase.ACTIVE
        nowMillis < expires + MISSION_GRACE_MS -> MissionPhase.READ_ONLY
        else -> MissionPhase.GONE
    }
}

/** "1h 05m", "12m 30s", "45s". Never negative. */
fun formatRemaining(remainingMs: Long): String {
    val total = (remainingMs.coerceAtLeast(0)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}

fun validateMission(spec: MissionSpec): String? = when {
    spec.title.trim().isEmpty() -> "Give the mission a title."
    spec.title.trim().length > MAX_MISSION_TITLE -> "The title can have at most $MAX_MISSION_TITLE characters."
    spec.goal.trim().length > MAX_MISSION_GOAL -> "The goal can have at most $MAX_MISSION_GOAL characters."
    MissionDuration.values().none { it.seconds == spec.durationSeconds } -> "Pick how long the mission lasts."
    else -> null
}

const val MAX_MISSION_TITLE = 60
const val MAX_MISSION_GOAL = 200
const val MAX_ANNOUNCEMENT = 500
const val MAX_OUTCOME = 1000
const val MAX_TASK_TEXT = 100

data class MissionTask(
    val id: String,
    val text: String,
    val done: Boolean,
    val doneBy: String,
    val order: Long
)

/** A private copy of text the user keeps after the room is gone. */
data class SavedNote(
    val id: String,
    val roomTitle: String,
    val author: String,
    val text: String,
    val source: String,
    val savedAt: Long?
)

interface MissionRepository {
    fun observeTasks(roomCode: String): Flow<List<MissionTask>>

    /** Owner only. */
    suspend fun addTask(roomCode: String, text: String, order: Long): Resource<Unit>

    /** Any member, while the mission is active. [done] false clears who finished it. */
    suspend fun setTaskDone(roomCode: String, taskId: String, done: Boolean): Resource<Unit>

    suspend fun deleteTask(roomCode: String, taskId: String): Resource<Unit>

    /** Owner only, while active. */
    suspend fun setAnnouncement(roomCode: String, text: String): Resource<Unit>

    /** Owner only, until the room is gone (so also during the read-only grace period). */
    suspend fun setOutcome(roomCode: String, text: String): Resource<Unit>

    suspend fun saveNote(roomTitle: String, author: String, text: String, source: String): Resource<Unit>

    fun observeSavedNotes(): Flow<List<SavedNote>>

    suspend fun deleteNote(noteId: String): Resource<Unit>
}
