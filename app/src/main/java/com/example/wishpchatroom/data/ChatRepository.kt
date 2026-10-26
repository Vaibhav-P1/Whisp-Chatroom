package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    val currentUid: String?

    /**
     * Streams the latest messages of a room, oldest first. The Firestore listener is attached while
     * the flow is collected and removed when collection stops. The flow fails if the listener errors.
     */
    fun observeMessages(roomCode: String): Flow<List<Message>>

    suspend fun getRoom(roomCode: String): Room?

    suspend fun sendMessage(roomCode: String, messageText: String, username: String): Resource<Unit>

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)

    /** Deletes the room only if it is temporary and the current user created it. */
    suspend fun deleteIfTemporaryOwner(roomCode: String)
}
