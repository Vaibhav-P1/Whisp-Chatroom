package com.example.wishpchatroom.data

import java.util.Date

data class Room(
    val roomCode: String = "",
    val creatorUid: String = "",
    val roomOpen: Boolean = true,
    // uid -> username chosen for this room
    val members: Map<String, String> = emptyMap(),
    val temporary: Boolean = false,
    val timestamp: Date? = null
)
