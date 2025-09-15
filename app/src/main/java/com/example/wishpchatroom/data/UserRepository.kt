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
            auth.createUserWithEmailAndPassword(email, password).await()
            val user = User(firstName, lastName, email)
            saveUserToFirestore(user)
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

    suspend fun getCurrentUser(): User? {
        return try {
            val currentUser = auth.currentUser
            if (currentUser != null) {
                val userDoc = firestore.collection("users")
                    .document(currentUser.email ?: "")
                    .get()
                    .await()
                userDoc.toObject(User::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun logout() {
        auth.signOut()
    }

    private suspend fun saveUserToFirestore(user: User) {
        firestore.collection("users").document(user.email).set(user).await()
    }
}