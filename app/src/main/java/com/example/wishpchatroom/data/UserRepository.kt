package com.example.wishpchatroom.data

interface UserRepository {
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Resource<Boolean>

    suspend fun login(email: String, password: String): Resource<Boolean>

    fun isLoggedIn(): Boolean

    suspend fun getCurrentUser(): User?

    fun logout()

    /** Removes locally cached data (such as chat history) so the next user on this device never sees it. */
    suspend fun clearLocalData()
}
