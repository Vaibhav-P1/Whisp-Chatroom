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
}