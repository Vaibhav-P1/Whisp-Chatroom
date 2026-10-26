package com.example.wishpchatroom.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException

/** Maps a Firebase failure to a message that is safe to show to the user. */
fun Throwable.toUserMessage(fallback: String): String = when (this) {
    is FirebaseNetworkException -> "No internet connection. Please check your network and try again."
    is FirebaseAuthException -> when (errorCode) {
        "ERROR_INVALID_EMAIL" -> "That email address looks invalid."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" ->
            "Incorrect email or password."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists for that email."
        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
        "ERROR_USER_DISABLED" -> "This account has been disabled."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please try again later."
        else -> fallback
    }
    is FirebaseFirestoreException -> when (code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "You don't have permission to do that."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "No internet connection. Please check your network and try again."
        else -> fallback
    }
    else -> fallback
}
