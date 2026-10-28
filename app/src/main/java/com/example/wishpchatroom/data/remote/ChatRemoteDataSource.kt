package com.example.wishpchatroom.data.remote

import com.example.wishpchatroom.data.Presence
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import kotlinx.coroutines.flow.Flow

/** A message as it exists on the server (or as a pending local write the server has not confirmed yet). */
data class RemoteMessage(
    val id: String,
    val uid: String,
    val username: String,
    val messageText: String,
    val timestamp: Long,
    val hasPendingWrites: Boolean,
    val replyTo: ReplyRef? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val ttlSeconds: Long? = null,
    val firstViewedAt: Long? = null
)

/** Everything the chat feature needs from the network. The repository decides what to cache. */
interface ChatRemoteDataSource {
    val currentUid: String?

    /** Streams the newest [limit] messages while collected; fails if the listener errors. */
    fun observeLatestMessages(roomCode: String, limit: Long): Flow<List<RemoteMessage>>

    /** One page of messages at or before [beforeTimestamp], newest first. */
    suspend fun fetchOlderMessages(roomCode: String, beforeTimestamp: Long, limit: Long): List<RemoteMessage>

    /** Writes a message under a caller-chosen [id] so that retrying never creates a duplicate. */
    suspend fun sendMessage(
        roomCode: String,
        id: String,
        username: String,
        messageText: String,
        replyTo: ReplyRef? = null,
        ttlSeconds: Long? = null
    ): Resource<Unit>

    /** Starts a whisper's timer. The server stamps the time; the rules allow it once, for non-authors. */
    suspend fun revealWhisper(roomCode: String, messageId: String): Resource<Unit>

    /** Best-effort removal of hidden whispers. Failures are ignored by the caller. */
    suspend fun deleteMessages(roomCode: String, messageIds: List<String>)

    suspend fun editMessage(roomCode: String, messageId: String, newText: String): Resource<Unit>

    /** Soft delete: the document stays, its text is cleared and it is flagged as deleted. */
    suspend fun deleteMessage(roomCode: String, messageId: String): Resource<Unit>

    /** Adds or removes the current user's reaction. */
    suspend fun setReaction(roomCode: String, messageId: String, emoji: String, add: Boolean): Resource<Unit>

    suspend fun getRoom(roomCode: String): Room?

    /** Streams the room document while collected. Emits null if the room no longer exists. */
    fun observeRoom(roomCode: String): Flow<Room?>

    /** Streams the presence documents of a room while collected. */
    fun observePresence(roomCode: String): Flow<List<Presence>>

    /** Removes the current user from the room's members. The owner cannot leave. */
    suspend fun leaveRoom(roomCode: String): Resource<Unit>

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)
}
