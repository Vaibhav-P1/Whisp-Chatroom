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

    /**
     * Cached messages whose text contains [pattern] (a LIKE pattern, already escaped with a
     * backslash), newest first. Deleted messages have no text left and never match.
     */
    @Query(
        "SELECT * FROM messages WHERE roomCode = :roomCode AND deleted = 0 " +
            "AND messageText LIKE :pattern ESCAPE '\\' " +
            // Hidden whispers, and whispers nobody has opened yet (unless they are mine), never match.
            "AND (hideAt IS NULL OR hideAt > :nowMillis) " +
            "AND (ttlSeconds IS NULL OR uid = :myUid OR firstViewedAt IS NOT NULL) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun search(roomCode: String, pattern: String, limit: Int, nowMillis: Long, myUid: String): Flow<List<MessageEntity>>

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

    @Query("UPDATE messages SET messageText = :messageText, edited = 1 WHERE id = :id")
    suspend fun applyEdit(id: String, messageText: String)

    @Query("UPDATE messages SET messageText = '', deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE id = :id")
    suspend fun updateReactions(id: String, reactionsJson: String)

    @Query("UPDATE messages SET firstViewedAt = :viewedAt, hideAt = :hideAt WHERE id = :id")
    suspend fun markViewed(id: String, viewedAt: Long, hideAt: Long?)

    /** Ids of hidden whispers of a room, for the best-effort deletion on the server. */
    @Query("SELECT id FROM messages WHERE roomCode = :roomCode AND hideAt IS NOT NULL AND hideAt <= :nowMillis LIMIT :limit")
    suspend fun hiddenIds(roomCode: String, nowMillis: Long, limit: Int): List<String>

    /** Removes every hidden whisper from the cache. */
    @Query("DELETE FROM messages WHERE hideAt IS NOT NULL AND hideAt <= :nowMillis")
    suspend fun purgeHidden(nowMillis: Long): Int

    @Query("DELETE FROM messages WHERE roomCode = :roomCode")
    suspend fun deleteRoom(roomCode: String)
}
