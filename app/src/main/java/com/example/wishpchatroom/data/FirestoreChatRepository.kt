package com.example.wishpchatroom.data

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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ChatRepository {
    override val currentUid: String? get() = auth.currentUser?.uid

    private fun roomRef(roomCode: String) = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

    /** Streams the latest [MESSAGE_LIMIT] messages; the listener lives as long as the flow is collected. */
    override fun observeMessages(roomCode: String): Flow<List<Message>> = callbackFlow {
        val registration = roomRef(roomCode)
            .collection(FirestoreCollections.MESSAGES)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MESSAGE_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            Message::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                    trySend(messages.reversed())
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun getRoom(roomCode: String): Room? {
        return try {
            roomRef(roomCode).get().await().toObject(Room::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun sendMessage(roomCode: String, messageText: String, username: String): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            // Membership and "room is open" are enforced by the security rules.
            roomRef(roomCode).collection(FirestoreCollections.MESSAGES).add(
                mapOf(
                    "uid" to uid,
                    "username" to username,
                    "messageText" to messageText,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            ).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error("Message not sent. The room may be closed.")
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

    /** Deletes the room only if it is temporary and the current user created it. */
    override suspend fun deleteIfTemporaryOwner(roomCode: String) {
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
