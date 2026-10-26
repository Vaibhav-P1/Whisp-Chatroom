package com.example.wishpchatroom.data.remote

import com.example.wishpchatroom.data.FirestoreCollections
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.toUserMessage
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreChatRemoteDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ChatRemoteDataSource {
    override val currentUid: String? get() = auth.currentUser?.uid

    private fun roomRef(roomCode: String) = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

    private fun messagesRef(roomCode: String) = roomRef(roomCode).collection(FirestoreCollections.MESSAGES)

    override fun observeLatestMessages(roomCode: String, limit: Long): Flow<List<RemoteMessage>> = callbackFlow {
        val registration = messagesRef(roomCode)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    trySend(snapshot.documents.mapNotNull { it.toRemoteMessage() })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun fetchOlderMessages(
        roomCode: String,
        beforeTimestamp: Long,
        limit: Long
    ): List<RemoteMessage> {
        val snapshot = messagesRef(roomCode)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .whereLessThanOrEqualTo("timestamp", Timestamp(Date(beforeTimestamp)))
            .limit(limit)
            .get()
            .await()
        return snapshot.documents.mapNotNull { it.toRemoteMessage() }
    }

    override suspend fun sendMessage(
        roomCode: String,
        id: String,
        username: String,
        messageText: String
    ): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            // Membership and "room is open" are enforced by the security rules.
            messagesRef(roomCode).document(id).set(
                mapOf(
                    "uid" to uid,
                    "username" to username,
                    "messageText" to messageText,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            ).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            // A retry of a write that did reach the server is rejected (messages cannot be
            // overwritten), so a message that already exists counts as delivered.
            if (messageExists(roomCode, id)) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Message not sent. The room may be closed.")
            }
        }
    }

    override suspend fun getRoom(roomCode: String): Room? {
        return try {
            roomRef(roomCode).get().await().toObject(Room::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun closeRoom(roomCode: String): Resource<Unit> {
        return try {
            roomRef(roomCode).update("roomOpen", false).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not close the room."))
        }
    }

    override suspend fun deleteRoom(roomCode: String): Resource<Unit> {
        return try {
            val room = roomRef(roomCode)
            deleteCollection(room.collection(FirestoreCollections.MESSAGES))
            deleteCollection(room.collection(FirestoreCollections.PRESENCE))
            room.delete().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not delete the room."))
        }
    }

    override suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {
        val uid = currentUid ?: return
        try {
            roomRef(roomCode).collection(FirestoreCollections.PRESENCE).document(uid).set(
                mapOf(
                    "username" to username,
                    "isPresent" to isPresent,
                    "lastSeen" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            // Presence is best effort and must never break the chat.
        }
    }

    private suspend fun messageExists(roomCode: String, id: String): Boolean {
        return try {
            messagesRef(roomCode).document(id).get().await().exists()
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes a collection in batches so large rooms stay within Firestore's 500 writes per batch. */
    private suspend fun deleteCollection(collection: CollectionReference) {
        while (true) {
            val snapshot = collection.limit(DELETE_BATCH_SIZE).get().await()
            if (snapshot.isEmpty) return

            val batch = firestore.batch()
            snapshot.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun DocumentSnapshot.toRemoteMessage(): RemoteMessage? {
        val uid = getString("uid") ?: return null
        val timestamp = getTimestamp("timestamp", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return RemoteMessage(
            id = id,
            uid = uid,
            username = getString("username").orEmpty(),
            messageText = getString("messageText").orEmpty(),
            timestamp = timestamp?.toDate()?.time ?: System.currentTimeMillis(),
            hasPendingWrites = metadata.hasPendingWrites()
        )
    }

    private companion object {
        const val DELETE_BATCH_SIZE = 400L
    }
}
