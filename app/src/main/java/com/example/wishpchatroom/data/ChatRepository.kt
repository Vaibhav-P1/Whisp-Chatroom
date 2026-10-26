package com.example.wishpchatroom.data

import com.google.firebase.firestore.ListenerRegistration

interface ChatRepository {
    val currentUid: String?

    /** Listens to the latest messages. The caller must remove the registration. */
    fun listenToMessages(
        roomCode: String,
        onMessagesChanged: (List<Message>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration

    suspend fun getRoom(roomCode: String): Room?

    suspend fun sendMessage(roomCode: String, messageText: String, username: String): Resource<Unit>

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)

    /** Deletes the room only if it is temporary and the current user created it. */
    suspend fun deleteIfTemporaryOwner(roomCode: String)
}
