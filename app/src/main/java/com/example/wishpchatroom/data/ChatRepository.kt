package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    fun getMessages(roomCode: String, onMessagesChanged: (List<Message>) -> Unit) {
        firestore.collection("rooms")
            .document(roomCode)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(Message::class.java)
                    }
                    onMessagesChanged(messages)
                }
            }
    }

    suspend fun sendMessage(roomCode: String, messageText: String, username: String) {
        try {
            // Check if room is still open before sending
            val roomDoc = firestore.collection("rooms").document(roomCode).get().await()
            val room = roomDoc.toObject(Room::class.java)

            if (room?.roomOpen == true) {
                val message = Message(
                    username = username,
                    messageText = messageText,
                    timestamp = System.currentTimeMillis()
                )

                firestore.collection("rooms")
                    .document(roomCode)
                    .collection("messages")
                    .add(message)
                    .await()
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun checkRoomOwnership(roomCode: String, callback: (Boolean) -> Unit) {
        try {
            val currentUser = auth.currentUser
            val roomDoc = firestore.collection("rooms").document(roomCode).get().await()
            val room = roomDoc.toObject(Room::class.java)

            val isOwner = room?.creatorUid == currentUser?.uid
            callback(isOwner)
        } catch (e: Exception) {
            callback(false)
        }
    }

    suspend fun closeRoom(roomCode: String) {
        try {
            firestore.collection("rooms").document(roomCode)
                .update("roomOpen", false)
                .await()
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun deleteRoom(roomCode: String) {
        try {
            // Delete all messages in the room
            val messagesSnapshot = firestore.collection("rooms")
                .document(roomCode)
                .collection("messages")
                .get()
                .await()

            for (messageDoc in messagesSnapshot.documents) {
                messageDoc.reference.delete().await()
            }

            // Delete all presence data
            val presenceSnapshot = firestore.collection("rooms")
                .document(roomCode)
                .collection("presence")
                .get()
                .await()

            for (presenceDoc in presenceSnapshot.documents) {
                presenceDoc.reference.delete().await()
            }

            // Delete the room document
            firestore.collection("rooms")
                .document(roomCode)
                .delete()
                .await()
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {
        try {
            val presenceData = mapOf(
                "username" to username,
                "isPresent" to isPresent,
                "lastSeen" to System.currentTimeMillis()
            )

            firestore.collection("rooms")
                .document(roomCode)
                .collection("presence")
                .document(username)
                .set(presenceData)
                .await()
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun checkAndDeleteTemporaryRoom(roomCode: String) {
        try {
            val currentUser = auth.currentUser ?: return

            val roomDoc = firestore.collection("rooms").document(roomCode).get().await()
            val room = roomDoc.toObject(Room::class.java) ?: return

            // Check if this is a temporary room and current user is the creator
            if (room.isTemporary && room.creatorUid == currentUser.uid) {
                // Delete the entire room
                deleteRoom(roomCode)
            }
        } catch (e: Exception) {
            // Handle error
        }
    }
}