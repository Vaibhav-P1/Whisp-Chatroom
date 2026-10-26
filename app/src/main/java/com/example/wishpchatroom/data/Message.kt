package com.example.wishpchatroom.data

/** Delivery state of a message. Only the sender's own messages are ever SENDING or FAILED. */
enum class MessageStatus { SENDING, SENT, FAILED }

data class Message(
    val id: String,
    val uid: String,
    val username: String,
    val messageText: String,
    // Epoch millis. A local estimate while sending, the server time once synced.
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENT
)
