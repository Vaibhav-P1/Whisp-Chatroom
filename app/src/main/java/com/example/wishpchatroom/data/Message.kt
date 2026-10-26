package com.example.wishpchatroom.data

/** Delivery state of a message. Only the sender's own messages are ever SENDING or FAILED. */
enum class MessageStatus { SENDING, SENT, FAILED }

/** The message a reply points at: enough to show a quote even if the original is not loaded. */
data class ReplyRef(
    val messageId: String,
    val username: String,
    val text: String
)

data class Message(
    val id: String,
    val uid: String,
    val username: String,
    val messageText: String,
    // Epoch millis. A local estimate while sending, the server time once synced.
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val replyTo: ReplyRef? = null,
    val edited: Boolean = false,
    // A deleted message keeps its place in the history but its text is gone.
    val deleted: Boolean = false,
    // Emoji -> uids of the members who reacted with it.
    val reactions: Map<String, List<String>> = emptyMap()
)

/** The reactions members can use. Must match the allowed set in firestore.rules. */
val SupportedReactions = listOf("👍", "😂", "😮", "😢", "🙏")
