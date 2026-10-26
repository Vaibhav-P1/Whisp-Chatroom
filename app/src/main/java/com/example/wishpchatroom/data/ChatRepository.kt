package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    val currentUid: String?

    /** The newest [limit] cached messages of a room, oldest first. Emits again whenever the cache changes. */
    fun observeMessages(roomCode: String, limit: Int): Flow<List<Message>>

    /**
     * Messages of a room that contain [query], newest first. Searches the history that is stored on
     * this device, so older messages that were never loaded are not found.
     */
    fun searchMessages(roomCode: String, query: String): Flow<List<Message>>

    /**
     * Keeps the local cache in sync with the server until the calling coroutine is cancelled.
     * Throws if the server listener fails (for example when the room was deleted).
     */
    suspend fun syncMessages(roomCode: String)

    /** Shows the message immediately as SENDING, then delivers it and marks it SENT or FAILED. */
    suspend fun sendMessage(
        roomCode: String,
        messageText: String,
        username: String,
        replyTo: ReplyRef? = null
    ): Resource<Unit>

    /** Changes the text of one of the user's own messages and marks it as edited. */
    suspend fun editMessage(roomCode: String, messageId: String, newText: String): Resource<Unit>

    /** Removes a message's text for everyone. Allowed for its author and for the room owner. */
    suspend fun deleteMessage(roomCode: String, messageId: String): Resource<Unit>

    /** Adds the current user's [emoji] reaction to a message, or removes it if already there. */
    suspend fun toggleReaction(roomCode: String, messageId: String, emoji: String): Resource<Unit>

    /** Sends a FAILED message again under the same id, so it can never be delivered twice. */
    suspend fun retryMessage(roomCode: String, messageId: String): Resource<Unit>

    /** True if the local cache holds more messages than the [shownCount] newest ones. Costs no network. */
    suspend fun hasCachedOlder(roomCode: String, shownCount: Int): Boolean

    /**
     * Downloads one page of messages older than anything cached into the cache. The result tells
     * whether there may be even older messages on the server.
     */
    suspend fun fetchOlderMessages(roomCode: String): Resource<Boolean>

    suspend fun getRoom(roomCode: String): Room?

    /** Streams the room while collected. Emits null once the room no longer exists (the owner deleted it). */
    fun observeRoom(roomCode: String): Flow<Room?>

    /** Streams who is in the room and when they were last seen. Fails once the room is gone. */
    fun observePresence(roomCode: String): Flow<List<Presence>>

    /** Leaves the room for good (frees the username) and forgets its cached messages. */
    suspend fun leaveRoom(roomCode: String): Resource<Unit>

    /** Forgets cached messages of a room that no longer exists. */
    suspend fun forgetRoom(roomCode: String)

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)

    /** Deletes the room only if it is temporary and the current user created it. */
    suspend fun deleteIfTemporaryOwner(roomCode: String)
}
