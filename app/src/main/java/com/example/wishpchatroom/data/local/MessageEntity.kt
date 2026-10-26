package com.example.wishpchatroom.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Delivery state of a message in the local cache. */
enum class MessageStatus { SENDING, SENT, FAILED }

/**
 * A chat message cached on the device. The cache is the single source of truth for the UI:
 * Firestore snapshots are written into it, and messages being sent are inserted before the
 * network write so they show up instantly.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["roomCode", "timestamp"])]
)
data class MessageEntity(
    // Also the Firestore document id, so a retry overwrites the same document instead of duplicating it.
    @PrimaryKey val id: String,
    val roomCode: String,
    val uid: String,
    val username: String,
    val messageText: String,
    // Epoch millis. A local estimate while sending, the server time once synced.
    val timestamp: Long,
    val status: MessageStatus
)
