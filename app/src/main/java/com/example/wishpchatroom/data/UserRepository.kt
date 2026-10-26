package com.example.wishpchatroom.data

interface UserRepository {
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Result<Boolean>

    suspend fun login(email: String, password: String): Result<Boolean>

    fun isLoggedIn(): Boolean

    suspend fun getCurrentUser(): User?

    fun logout()
}
