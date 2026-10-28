package com.example.wishpchatroom.data

import com.example.wishpchatroom.data.local.AppDatabase
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreUserRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val database: AppDatabase
) : UserRepository {
    override suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Resource<Boolean> =
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
            Resource.Success(true)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Sign up failed. Please try again."))
        }

    override suspend fun login(email: String, password: String): Resource<Boolean> =
        try {
            auth.signInWithEmailAndPassword(email, password).await()
            Resource.Success(true)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Login failed. Please try again."))
        }

    override suspend fun sendPasswordReset(email: String): Resource<Unit> =
        try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Resource.Success(Unit)
        } catch (e: FirebaseAuthException) {
            // Do not reveal whether an account exists for this address.
            if (e.errorCode == "ERROR_USER_NOT_FOUND") Resource.Success(Unit)
            else Resource.Error(e.toUserMessage("Could not send the reset email. Please try again."))
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not send the reset email. Please try again."))
        }

    override suspend fun deleteAccount(password: String): Resource<Unit> {
        val user = auth.currentUser ?: return Resource.Error("You must be logged in")
        val email = user.email ?: return Resource.Error("This account cannot be deleted from the app.")
        return try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()

            // Profile data first, while the rules still see us as the owner; the Auth user last so
            // a failure in between leaves an account the user can still sign in to and retry.
            val userDoc = firestore.collection(FirestoreCollections.USERS).document(user.uid)
            val blocks = userDoc.collection(FirestoreCollections.BLOCKS).get().await()
            firestore.batch().apply {
                blocks.documents.forEach { delete(it.reference) }
                delete(userDoc)
            }.commit().await()

            user.delete().await()
            clearLocalData()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not delete the account. Please try again."))
        }
    }

    override fun isLoggedIn(): Boolean = auth.currentUser != null

    override suspend fun getCurrentUser(): User? {
        val currentUser = auth.currentUser ?: return null
        return try {
            val userDoc = firestore.collection(FirestoreCollections.USERS).document(currentUser.uid).get().await()
            userDoc.toObject(User::class.java)
                ?: createFallbackProfile(currentUser.uid, currentUser.email.orEmpty())
        } catch (e: Exception) {
            null
        }
    }

    override fun logout() {
        auth.signOut()
    }

    override suspend fun clearLocalData() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }

    // Accounts created before profiles were keyed by uid have no profile document yet.
    private suspend fun createFallbackProfile(uid: String, email: String): User {
        val user = User(firstName = email.substringBefore('@').ifBlank { "User" }, lastName = "-", email = email)
        saveUserToFirestore(uid, user)
        return user
    }

    private suspend fun saveUserToFirestore(uid: String, user: User) {
        firestore.collection(FirestoreCollections.USERS).document(uid).set(user).await()
    }
}
