package com.example.wishpchatroom.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.wishpchatroom.data.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** The newest [limit] messages of a room, newest first. */
    @Query("SELECT * FROM messages WHERE roomCode = :roomCode ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeLatest(roomCode: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE roomCode = :roomCode")
    suspend fun count(roomCode: String): Int

    /** Timestamp of the oldest message that is known to exist on the server. */
    @Query("SELECT MIN(timestamp) FROM messages WHERE roomCode = :roomCode AND status = 'SENT'")
    suspend fun oldestSentTimestamp(roomCode: String): Long?

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: MessageStatus)

    @Query("DELETE FROM messages WHERE roomCode = :roomCode")
    suspend fun deleteRoom(roomCode: String)
}
