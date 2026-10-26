package com.example.wishpchatroom.data

import com.google.firebase.firestore.DocumentId
import java.util.Date

data class Message(
    @DocumentId val id: String = "",
    val uid: String = "",
    val username: String = "",
    val messageText: String = "",
    val timestamp: Date? = null
)
