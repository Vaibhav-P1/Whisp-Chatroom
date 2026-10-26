package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    val currentUid: String? get() = auth.currentUser?.uid

    private fun roomRef(roomCode: String) = firestore.collection("rooms").document(roomCode)

    /** Listens to the latest [MESSAGE_LIMIT] messages. The caller must remove the registration. */
    fun listenToMessages(
        roomCode: String,
        onMessagesChanged: (List<Message>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return roomRef(roomCode)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MESSAGE_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            Message::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                    onMessagesChanged(messages.reversed())
                }
            }
    }

    suspend fun getRoom(roomCode: String): Room? {
        return try {
            roomRef(roomCode).get().await().toObject(Room::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun sendMessage(roomCode: String, messageText: String, username: String): Result<Unit> {
        val uid = currentUid ?: return Result.Error(Exception("You must be logged in"))
        return try {
            // Membership and "room is open" are enforced by the security rules.
            roomRef(roomCode).collection("messages").add(
                mapOf(
                    "uid" to uid,
                    "username" to username,
                    "messageText" to messageText,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(Exception("Message not sent. The room may be closed.", e))
        }
    }

    suspend fun closeRoom(roomCode: String): Result<Unit> {
        return try {
            roomRef(roomCode).update("roomOpen", false).await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun deleteRoom(roomCode: String): Result<Unit> {
        return try {
            val room = roomRef(roomCode)
            deleteCollection(room.collection("messages"))
            deleteCollection(room.collection("presence"))
            room.delete().await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {
        val uid = currentUid ?: return
        try {
            roomRef(roomCode).collection("presence").document(uid).set(
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

    /** Deletes the room only if it is temporary and the current user created it. */
    suspend fun deleteIfTemporaryOwner(roomCode: String) {
        val uid = currentUid ?: return
        val room = getRoom(roomCode) ?: return
        if (room.temporary && room.creatorUid == uid) {
            deleteRoom(roomCode)
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

    private companion object {
        const val MESSAGE_LIMIT = 100L
        const val DELETE_BATCH_SIZE = 400L
    }
}
