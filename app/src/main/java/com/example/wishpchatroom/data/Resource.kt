package com.example.wishpchatroom.data

/** Outcome of a data operation. [Error] carries a message that is safe to show to the user. */
sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
}
