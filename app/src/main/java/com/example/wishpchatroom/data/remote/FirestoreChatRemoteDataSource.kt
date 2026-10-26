package com.example.wishpchatroom.data.remote

import com.example.wishpchatroom.data.FirestoreCollections
import com.example.wishpchatroom.data.Presence
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.toUserMessage
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
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
            // Metadata changes are included so that a message is re-emitted once the server has
            // confirmed it (its pending-write flag clears) even if its data did not change, such as
            // after a reaction. They are local events and cost no reads.
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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
        messageText: String,
        replyTo: ReplyRef?
    ): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            val data = mutableMapOf<String, Any>(
                "uid" to uid,
                "username" to username,
                "messageText" to messageText,
                "timestamp" to FieldValue.serverTimestamp()
            )
            if (replyTo != null) {
                data["replyTo"] = mapOf(
                    "id" to replyTo.messageId,
                    "username" to replyTo.username,
                    "text" to replyTo.text
                )
            }
            // Membership and "room is open" are enforced by the security rules.
            messagesRef(roomCode).document(id).set(data).await()
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

    override suspend fun editMessage(roomCode: String, messageId: String, newText: String): Resource<Unit> {
        return try {
            messagesRef(roomCode).document(messageId)
                .update(mapOf("messageText" to newText, "edited" to true))
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not edit the message."))
        }
    }

    override suspend fun deleteMessage(roomCode: String, messageId: String): Resource<Unit> {
        return try {
            messagesRef(roomCode).document(messageId)
                .update(mapOf("deleted" to true, "messageText" to ""))
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not delete the message."))
        }
    }

    override suspend fun setReaction(
        roomCode: String,
        messageId: String,
        emoji: String,
        add: Boolean
    ): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            messagesRef(roomCode).document(messageId).update(
                FieldPath.of("reactions", emoji),
                if (add) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            ).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not update the reaction."))
        }
    }

    override suspend fun getRoom(roomCode: String): Room? {
        return try {
            roomRef(roomCode).get().await().toObject(Room::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun observeRoom(roomCode: String): Flow<Room?> = callbackFlow {
        val registration = roomRef(roomCode).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                trySend(if (snapshot.exists()) snapshot.toObject(Room::class.java) else null)
            }
        }
        awaitClose { registration.remove() }
    }

    override fun observePresence(roomCode: String): Flow<List<Presence>> = callbackFlow {
        val registration = roomRef(roomCode).collection(FirestoreCollections.PRESENCE)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    trySend(
                        snapshot.documents.map { doc ->
                            Presence(
                                uid = doc.id,
                                isPresent = doc.getBoolean("isPresent") ?: false,
                                lastSeen = doc.getTimestamp(
                                    "lastSeen",
                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                )?.toDate()?.time ?: 0L
                            )
                        }
                    )
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun leaveRoom(roomCode: String): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            // Presence first: once the user is no longer a member the rules would reject this delete.
            try {
                roomRef(roomCode).collection(FirestoreCollections.PRESENCE).document(uid).delete().await()
            } catch (e: Exception) {
                // Presence is best effort; leaving must still go through.
            }
            roomRef(roomCode).update(FieldPath.of("members", uid), FieldValue.delete()).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not leave the room."))
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
            hasPendingWrites = metadata.hasPendingWrites(),
            replyTo = readReply(),
            edited = getBoolean("edited") ?: false,
            deleted = getBoolean("deleted") ?: false,
            reactions = readReactions()
        )
    }

    private fun DocumentSnapshot.readReply(): ReplyRef? {
        val reply = get("replyTo") as? Map<*, *> ?: return null
        val messageId = reply["id"] as? String ?: return null
        return ReplyRef(
            messageId = messageId,
            username = reply["username"] as? String ?: "",
            text = reply["text"] as? String ?: ""
        )
    }

    private fun DocumentSnapshot.readReactions(): Map<String, List<String>> {
        val reactions = get("reactions") as? Map<*, *> ?: return emptyMap()
        return reactions.entries
            .mapNotNull { (emoji, uids) ->
                val key = emoji as? String ?: return@mapNotNull null
                key to (uids as? List<*>).orEmpty().filterIsInstance<String>()
            }
            .filter { (_, uids) -> uids.isNotEmpty() }
            .toMap()
    }

    private companion object {
        const val DELETE_BATCH_SIZE = 400L
    }
}
