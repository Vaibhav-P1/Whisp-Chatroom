package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Result<Boolean> =
        try {
            val firebaseUser = auth.createUserWithEmailAndPassword(email, password).await().user
                ?: throw IllegalStateException("Account was created but no user was returned")

            try {
                saveUserToFirestore(firebaseUser.uid, User(firstName.trim(), lastName.trim(), email))
            } catch (e: Exception) {
                // Roll back so the user can retry instead of being stuck with an account
                // that has no profile ("email already in use").
                firebaseUser.delete().await()
                throw e
            }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(e)
        }

    suspend fun login(email: String, password: String): Result<Boolean> =
        try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(e)
        }

    fun isLoggedIn(): Boolean = auth.currentUser != null

    suspend fun getCurrentUser(): User? {
        val currentUser = auth.currentUser ?: return null
        return try {
            val userDoc = firestore.collection("users").document(currentUser.uid).get().await()
            userDoc.toObject(User::class.java)
                ?: createFallbackProfile(currentUser.uid, currentUser.email.orEmpty())
        } catch (e: Exception) {
            null
        }
    }

    fun logout() {
        auth.signOut()
    }

    // Accounts created before profiles were keyed by uid have no profile document yet.
    private suspend fun createFallbackProfile(uid: String, email: String): User {
        val user = User(firstName = email.substringBefore('@').ifBlank { "User" }, lastName = "-", email = email)
        saveUserToFirestore(uid, user)
        return user
    }

    private suspend fun saveUserToFirestore(uid: String, user: User) {
        firestore.collection("users").document(uid).set(user).await()
    }
}
