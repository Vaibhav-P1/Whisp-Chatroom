package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

/** Must match the categories allowed in firestore.rules. */
enum class ReportCategory(val wire: String, val label: String) {
    SPAM("spam", "Spam"),
    HARASSMENT("harassment", "Harassment or bullying"),
    HATE("hate", "Hate speech"),
    SEXUAL("sexual", "Sexual content"),
    VIOLENCE("violence", "Violence or threats"),
    SELF_HARM("self_harm", "Self-harm"),
    ILLEGAL("illegal", "Illegal activity"),
    OTHER("other", "Something else")
}

/** A member the room owner removed and banned. */
data class Ban(val uid: String, val username: String)

interface ModerationRepository {
    /** The uids the current user has blocked. Private: nobody else can read this list. */
    fun observeBlockedUids(): Flow<Set<String>>

    suspend fun blockUser(uid: String): Resource<Unit>

    suspend fun unblockUser(uid: String): Resource<Unit>

    /**
     * Files a report that only the project admin can read. [messageText] is copied into the report
     * because the message may later be edited or deleted.
     */
    suspend fun reportUser(
        roomCode: String,
        targetUid: String,
        messageId: String?,
        messageText: String?,
        category: ReportCategory,
        description: String
    ): Resource<Unit>

    /** Owner only: removes a member from the room, bans them from rejoining and logs it. */
    suspend fun removeAndBan(roomCode: String, targetUid: String, targetName: String): Resource<Unit>

    /** Owner only: lets a banned user rejoin with the room code again. */
    suspend fun unban(roomCode: String, uid: String): Resource<Unit>

    /** Owner only: streams the room's ban list. */
    fun observeBans(roomCode: String): Flow<List<Ban>>
}
