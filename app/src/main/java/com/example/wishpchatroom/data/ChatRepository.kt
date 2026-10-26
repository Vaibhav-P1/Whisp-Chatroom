package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    val currentUid: String?

    /** The newest [limit] cached messages of a room, oldest first. Emits again whenever the cache changes. */
    fun observeMessages(roomCode: String, limit: Int): Flow<List<Message>>

    /**
     * Keeps the local cache in sync with the server until the calling coroutine is cancelled.
     * Throws if the server listener fails (for example when the room was deleted).
     */
    suspend fun syncMessages(roomCode: String)

    /** Shows the message immediately as SENDING, then delivers it and marks it SENT or FAILED. */
    suspend fun sendMessage(roomCode: String, messageText: String, username: String): Resource<Unit>

    /** Sends a FAILED message again under the same id, so it can never be delivered twice. */
    suspend fun retryMessage(roomCode: String, messageId: String): Resource<Unit>

    suspend fun getRoom(roomCode: String): Room?

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)

    /** Deletes the room only if it is temporary and the current user created it. */
    suspend fun deleteIfTemporaryOwner(roomCode: String)
}
