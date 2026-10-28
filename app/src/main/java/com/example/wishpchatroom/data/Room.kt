package com.example.wishpchatroom.data

import java.util.Date

data class Room(
    val roomCode: String = "",
    val creatorUid: String = "",
    val roomOpen: Boolean = true,
    // uid -> username chosen for this room
    val members: Map<String, String> = emptyMap(),
    val temporary: Boolean = false,
    val timestamp: Date? = null,
    // "code" (absent on older rooms) or "dm", a two-member room between friends.
    val type: String = "code",
    val locked: Boolean = false,
    val lockedBy: String = "",
    val lastMessage: LastMessage? = null,
    val lastMessageAt: Date? = null
)

/** Preview of the newest message, kept on the room document so the inbox needs no message query. */
data class LastMessage(
    val text: String = "",
    val uid: String = "",
    val at: Date? = null
)
