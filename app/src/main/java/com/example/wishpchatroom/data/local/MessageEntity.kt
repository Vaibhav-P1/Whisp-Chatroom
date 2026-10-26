package com.example.wishpchatroom.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.MessageStatus
import com.example.wishpchatroom.data.ReplyRef
import org.json.JSONArray
import org.json.JSONObject

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
    val status: MessageStatus,
    val replyToId: String? = null,
    val replyToUsername: String? = null,
    val replyToText: String? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    // Reactions as JSON ({"👍":["uid1","uid2"]}) so the table stays flat.
    val reactionsJson: String = "{}"
)

fun MessageEntity.toDomain() = Message(
    id = id,
    uid = uid,
    username = username,
    messageText = messageText,
    timestamp = timestamp,
    status = status,
    replyTo = replyToId?.let { ReplyRef(it, replyToUsername.orEmpty(), replyToText.orEmpty()) },
    edited = edited,
    deleted = deleted,
    reactions = decodeReactions(reactionsJson)
)

fun encodeReactions(reactions: Map<String, List<String>>): String {
    val json = JSONObject()
    reactions.forEach { (emoji, uids) -> json.put(emoji, JSONArray(uids)) }
    return json.toString()
}

fun decodeReactions(value: String): Map<String, List<String>> {
    return try {
        val json = JSONObject(value)
        json.keys().asSequence().associateWith { emoji ->
            val array = json.getJSONArray(emoji)
            List(array.length()) { index -> array.getString(index) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
