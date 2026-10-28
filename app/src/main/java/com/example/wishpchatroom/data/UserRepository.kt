package com.example.wishpchatroom.data

interface UserRepository {
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Resource<Boolean>

    suspend fun login(email: String, password: String): Resource<Boolean>

    /**
     * Asks Firebase to email a reset link. An address with no account still reports success so the
     * response never reveals which emails are registered.
     */
    suspend fun sendPasswordReset(email: String): Resource<Unit>

    /**
     * Permanently deletes the signed-in account: re-authenticates with [password], removes the
     * user's profile and block list, clears the local cache and deletes the Auth user.
     */
    suspend fun deleteAccount(password: String): Resource<Unit>

    fun isLoggedIn(): Boolean

    suspend fun getCurrentUser(): User?

    fun logout()

    /** Removes locally cached data (such as chat history) so the next user on this device never sees it. */
    suspend fun clearLocalData()
}
