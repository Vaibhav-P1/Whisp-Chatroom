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
    val reactions: Map<String, List<String>> = emptyMap(),
    // Whisper: seconds it stays visible after it is first opened by someone other than the author.
    val ttlSeconds: Long? = null,
    // Epoch millis when it was first opened (server time), null until then.
    val firstViewedAt: Long? = null
) {
    val isWhisper: Boolean get() = ttlSeconds != null

    /** When this message stops being shown, or null for a normal message. */
    val hideAt: Long? get() = whisperHideAt(timestamp, ttlSeconds, firstViewedAt)

    /** A whisper nobody else has opened yet: the text stays hidden behind a tap. */
    fun isSealedFor(uid: String?): Boolean = isWhisper && firstViewedAt == null && this.uid != uid

    fun isVisibleAt(nowMillis: Long): Boolean = hideAt?.let { nowMillis < it } ?: true
}

/** A never-opened whisper is hidden 24 hours after sending, whatever else happens. */
const val WHISPER_CAP_MS = 24L * 60 * 60 * 1000

val WhisperLifetimes = listOf(10L to "10 seconds", 60L to "1 minute", 3_600L to "1 hour")

/** Mirrors firestore.rules: opened + ttl, but never later than the cap after sending. */
fun whisperHideAt(sentAt: Long, ttlSeconds: Long?, firstViewedAt: Long?): Long? {
    if (ttlSeconds == null) return null
    val cap = sentAt + WHISPER_CAP_MS
    return if (firstViewedAt == null) cap else minOf(firstViewedAt + ttlSeconds * 1000, cap)
}

/** The reactions members can use. Must match the allowed set in firestore.rules. */
val SupportedReactions = listOf("👍", "😂", "😮", "😢", "🙏")
