package com.example.wishpchatroom.data

data class Message(
    val username: String = "",
    val messageText: String = "",
    val timestamp: Long = 0L
)