package com.example.wishpchatroom.data.remote

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
    val hasPendingWrites: Boolean
)

/** Everything the chat feature needs from the network. The repository decides what to cache. */
interface ChatRemoteDataSource {
    val currentUid: String?

    /** Streams the newest [limit] messages while collected; fails if the listener errors. */
    fun observeLatestMessages(roomCode: String, limit: Long): Flow<List<RemoteMessage>>

    /** One page of messages at or before [beforeTimestamp], newest first. */
    suspend fun fetchOlderMessages(roomCode: String, beforeTimestamp: Long, limit: Long): List<RemoteMessage>

    /** Writes a message under a caller-chosen [id] so that retrying never creates a duplicate. */
    suspend fun sendMessage(roomCode: String, id: String, username: String, messageText: String): Resource<Unit>

    suspend fun getRoom(roomCode: String): Room?

    suspend fun closeRoom(roomCode: String): Resource<Unit>

    suspend fun deleteRoom(roomCode: String): Resource<Unit>

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean)
}
