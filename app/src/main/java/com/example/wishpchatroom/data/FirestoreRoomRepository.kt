package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import java.security.SecureRandom

@Singleton
class FirestoreRoomRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : RoomRepository {
    override suspend fun createRoom(username: String, isTemporary: Boolean, mission: MissionSpec?): Resource<String> {
        val uid = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        val name = username.trim()
        validateUsername(name)?.let { return Resource.Error(it) }
        mission?.let { spec -> validateMission(spec)?.let { return Resource.Error(it) } }

        return try {
            repeat(MAX_CODE_ATTEMPTS) {
                val roomCode = generateRoomCode()
                val roomRef = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

                // Never overwrite an existing room that happens to share the generated code.
                val created = firestore.runTransaction { transaction ->
                    if (transaction.get(roomRef).exists()) {
                        false
                    } else {
                        val data = mutableMapOf<String, Any>(
                            "roomCode" to roomCode,
                            "creatorUid" to uid,
                            "roomOpen" to true,
                            "members" to mapOf(uid to name),
                            "temporary" to isTemporary,
                            "timestamp" to FieldValue.serverTimestamp()
                        )
                        // The rules derive the expiry from "timestamp" plus this duration.
                        if (mission != null) {
                            data["mission"] = mapOf(
                                "title" to mission.title.trim(),
                                "goal" to mission.goal.trim(),
                                "durationSeconds" to mission.durationSeconds
                            )
                        }
                        transaction.set(roomRef, data)
                        true
                    }
                }.await()

                if (created) return Resource.Success(roomCode)
            }
            Resource.Error("Could not generate a unique room code. Please try again.")
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not create the room. Please try again."))
        }
    }

    override suspend fun joinRoom(roomCode: String, username: String): Resource<Boolean> {
        val uid = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        val name = username.trim()
        validateUsername(name)?.let { return Resource.Error(it) }

        return try {
            val roomRef = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

            // The username check and the write happen in one transaction so two people
            // joining at the same time cannot claim the same name.
            val failure = firestore.runTransaction { transaction ->
                val snapshot = transaction.get(roomRef)
                val room = snapshot.toObject(Room::class.java)

                when {
                    !snapshot.exists() || room == null -> "Room does not exist"
                    !room.roomOpen -> "Room is closed"
                    room.members.containsKey(uid) -> null // already a member, just rejoin
                    room.members.values.any { it.equals(name, ignoreCase = true) } ->
                        "Username already exists in this room. Please choose a different name."
                    else -> {
                        transaction.update(roomRef, FieldPath.of("members", uid), name)
                        null
                    }
                }
            }.await()

            if (failure != null) Resource.Error(failure) else Resource.Success(true)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not join the room. Please try again."))
        }
    }

    private fun validateUsername(name: String): String? = when {
        name.isEmpty() -> "Username cannot be empty"
        name.length > MAX_USERNAME_LENGTH -> "Username must be at most $MAX_USERNAME_LENGTH characters"
        else -> null
    }

    private fun generateRoomCode(): String {
        return (1..ROOM_CODE_LENGTH)
            .map { ROOM_CODE_CHARS[secureRandom.nextInt(ROOM_CODE_CHARS.length)] }
            .joinToString("")
    }

    private companion object {
        const val ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val ROOM_CODE_LENGTH = 6
        const val MAX_CODE_ATTEMPTS = 5
        const val MAX_USERNAME_LENGTH = 30
        val secureRandom = SecureRandom()
    }
}
