package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class RoomRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    suspend fun createRoom(username: String, isTemporary: Boolean): Result<String> {
        return try {
            val roomCode = generateRoomCode()
            val currentUser = auth.currentUser

            val room = Room(
                roomCode = roomCode,
                creatorUid = currentUser?.uid ?: "",
                creatorEmail = currentUser?.email ?: "",
                roomOpen = true,
                isTemporary = isTemporary,
                participants = listOf(username),
                timestamp = System.currentTimeMillis()
            )

            firestore.collection("rooms").document(roomCode).set(room).await()
            Result.Success(roomCode)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun joinRoom(roomCode: String, username: String): Result<Boolean> {
        return try {
            val roomDoc = firestore.collection("rooms").document(roomCode).get().await()

            if (!roomDoc.exists()) {
                return Result.Error(Exception("Room does not exist"))
            }

            val room = roomDoc.toObject(Room::class.java)
            if (room?.roomOpen != true) {
                return Result.Error(Exception("Room is closed"))
            }

            // Check if username already exists in the room
            val participants = room.participants.toMutableList()
            if (participants.contains(username)) {
                return Result.Error(Exception("Username already exists in this room. Please choose a different name."))
            }

            // Add user to participants
            participants.add(username)
            firestore.collection("rooms").document(roomCode)
                .update("participants", participants).await()

            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
}