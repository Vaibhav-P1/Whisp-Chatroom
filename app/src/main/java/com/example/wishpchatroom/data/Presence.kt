package com.example.wishpchatroom.data

/** Last known presence of a room member. [lastSeen] is epoch millis, 0 if never written. */
data class Presence(
    val uid: String,
    val isPresent: Boolean,
    val lastSeen: Long
)
