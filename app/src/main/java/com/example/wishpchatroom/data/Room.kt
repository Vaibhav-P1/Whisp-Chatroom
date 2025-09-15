package com.example.wishpchatroom.data

data class Room(
    val roomCode: String = "",
    val creatorUid: String = "",
    val creatorEmail: String = "",
    val roomOpen: Boolean = true,
    val participants: List<String> = emptyList(),
    val isTemporary: Boolean = false,
    val timestamp: Long = 0L
)