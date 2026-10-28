package com.example.wishpchatroom.data

import com.google.firebase.firestore.IgnoreExtraProperties

// The profile document also holds handle fields that only the contacts code reads.
@IgnoreExtraProperties
data class User(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = ""
)