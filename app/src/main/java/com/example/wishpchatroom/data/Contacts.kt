package com.example.wishpchatroom.data

import kotlinx.coroutines.flow.Flow

/** A public handle and the name shown next to it (first name and last initial). */
data class HandleInfo(val handle: String, val uid: String, val displayName: String)

data class FriendRequest(
    val id: String,
    val from: String,
    val to: String,
    val fromHandle: String,
    val fromName: String
)

data class Friend(val uid: String, val handle: String)

/** One row of the inbox: a direct message conversation with a friend. */
data class InboxItem(
    val roomCode: String,
    val friendUid: String,
    val friendName: String,
    val friendHandle: String,
    val lastText: String?,
    val lastSenderUid: String?,
    // Epoch millis of the newest message, null for a conversation with no messages yet.
    val lastAt: Long?,
    val unread: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val locked: Boolean
)

/** Per-conversation preferences, stored privately in users/{me}/chats/{roomCode}. */
data class ChatPrefs(
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val lastReadAt: Long? = null
)

private val HANDLE_PATTERN = Regex("[a-z][a-z0-9_]{3,19}")

// Must match firestore.rules.
private val RESERVED_HANDLES = setOf(
    "admin", "support", "wishp", "whisp", "moderator", "system", "null",
    "undefined", "root", "official", "help", "api"
)

const val HANDLE_CHANGE_DAYS = 30

/** Returns why [handle] cannot be used, or null if it is acceptable. Mirrors firestore.rules. */
fun validateHandle(handle: String): String? = when {
    !HANDLE_PATTERN.matches(handle) ->
        "Use 4 to 20 characters: start with a letter, then lowercase letters, numbers or underscores."
    handle.endsWith("_") -> "A handle cannot end with an underscore."
    handle in RESERVED_HANDLES -> "That handle is reserved."
    else -> null
}

/** The id of the one direct message room between two users: the smaller uid always comes first. */
fun dmRoomId(a: String, b: String): String = if (a < b) "dm_${a}_$b" else "dm_${b}_$a"

fun isDmRoom(roomCode: String): Boolean = roomCode.startsWith("dm_")

/** Orders the inbox: pinned first, then newest message, then name. Conversations with no message come last. */
fun sortInbox(items: List<InboxItem>): List<InboxItem> =
    items.sortedWith(
        compareByDescending<InboxItem> { it.pinned }
            .thenByDescending { it.lastAt ?: Long.MIN_VALUE }
            .thenBy { it.friendName.lowercase() }
    )

interface ContactsRepository {
    val currentUid: String?

    /** The signed-in user's own handle, null until they claim one. */
    fun observeMyHandle(): Flow<String?>

    /**
     * Claims [handle] for the signed-in user, releasing the previous one. Fails if it is taken or
     * if the handle was already changed in the last 30 days.
     */
    suspend fun claimHandle(handle: String): Resource<Unit>

    /** Exact lookup (handles can never be listed). Success(null) means nobody has this handle. */
    suspend fun findByHandle(handle: String): Resource<HandleInfo?>

    suspend fun sendRequest(handle: String): Resource<Unit>

    fun observeIncomingRequests(): Flow<List<FriendRequest>>

    fun observeOutgoingRequests(): Flow<List<FriendRequest>>

    /** Accepts a request: both friend records and the direct message room are written in one batch. */
    suspend fun acceptRequest(request: FriendRequest): Resource<String>

    /** Declines an incoming request or cancels an outgoing one. */
    suspend fun removeRequest(request: FriendRequest): Resource<Unit>

    fun observeFriends(): Flow<List<Friend>>

    /** Removes the friendship and locks the direct message, which keeps its history. */
    suspend fun removeFriend(friendUid: String): Resource<Unit>

    /** The conversations with friends. Listeners exist only while this flow is collected. */
    fun observeInbox(): Flow<List<InboxItem>>

    suspend fun setPinned(roomCode: String, pinned: Boolean): Resource<Unit>

    suspend fun setMuted(roomCode: String, muted: Boolean): Resource<Unit>

    suspend fun setArchived(roomCode: String, archived: Boolean): Resource<Unit>

    /** Marks a conversation as read up to now. */
    suspend fun markRead(roomCode: String)
}
