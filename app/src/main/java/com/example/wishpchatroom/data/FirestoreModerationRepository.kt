package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreModerationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ModerationRepository {

    private fun blocks(uid: String) = firestore.collection(FirestoreCollections.USERS)
        .document(uid).collection(FirestoreCollections.BLOCKS)

    private fun room(roomCode: String) = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

    override fun observeBlockedUids(): Flow<Set<String>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptySet())
            close()
            return@callbackFlow
        }
        val registration = blocks(uid).addSnapshotListener { snapshot, error ->
            // A failing block list must not break the chat: show everyone instead.
            if (error != null) trySend(emptySet())
            else if (snapshot != null) trySend(snapshot.documents.map { it.id }.toSet())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun blockUser(uid: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        if (uid == me) return Resource.Error("You can't block yourself.")
        return try {
            blocks(me).document(uid).set(mapOf("createdAt" to FieldValue.serverTimestamp())).await()
            unfriendAndLockDm(me, uid)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not block this user. Please try again."))
        }
    }

    // Blocking someone you are friends with ends the friendship and locks the DM, which looks the
    // same to them as an unfriend. Best effort: there is nothing to do when you were not friends.
    private suspend fun unfriendAndLockDm(me: String, other: String) {
        try {
            val users = firestore.collection(FirestoreCollections.USERS)
            firestore.batch()
                .delete(users.document(me).collection(FirestoreCollections.FRIENDS).document(other))
                .delete(users.document(other).collection(FirestoreCollections.FRIENDS).document(me))
                .update(room(dmRoomId(me, other)), mapOf("locked" to true, "lockedBy" to me))
                .commit().await()
        } catch (e: Exception) {
            // Not friends (no DM to lock): the block itself already succeeded.
        }
    }

    override suspend fun unblockUser(uid: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        return try {
            blocks(me).document(uid).delete().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not unblock this user. Please try again."))
        }
    }

    override suspend fun reportUser(
        roomCode: String,
        targetUid: String,
        messageId: String?,
        messageText: String?,
        category: ReportCategory,
        description: String
    ): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        if (targetUid == me) return Resource.Error("You can't report yourself.")
        val report = mutableMapOf<String, Any>(
            "reporterUid" to me,
            "targetUid" to targetUid,
            "roomId" to roomCode,
            "category" to category.wire,
            "description" to description.trim().take(MAX_REPORT_TEXT),
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (messageId != null) report["messageId"] = messageId
        if (messageText != null) report["snapshot"] = messageText.take(MAX_REPORT_TEXT)
        return try {
            firestore.collection(FirestoreCollections.REPORTS).add(report).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not send the report. Please try again."))
        }
    }

    override suspend fun removeAndBan(roomCode: String, targetUid: String, targetName: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        if (targetUid == me) return Resource.Error("You can't remove yourself.")
        return try {
            val roomRef = room(roomCode)
            // One batch: the member can never be removed without the ban and the log entry.
            firestore.batch()
                .update(roomRef, FieldPath.of("members", targetUid), FieldValue.delete())
                .set(
                    roomRef.collection(FirestoreCollections.BANS).document(targetUid),
                    mapOf(
                        "username" to targetName,
                        "bannedAt" to FieldValue.serverTimestamp(),
                        "reason" to ""
                    )
                )
                .delete(roomRef.collection(FirestoreCollections.PRESENCE).document(targetUid))
                .set(
                    roomRef.collection(FirestoreCollections.MODLOG).document(),
                    modLogEntry("ban", targetUid, me)
                )
                .commit().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not remove this member. Please try again."))
        }
    }

    override suspend fun unban(roomCode: String, uid: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        return try {
            val roomRef = room(roomCode)
            firestore.batch()
                .delete(roomRef.collection(FirestoreCollections.BANS).document(uid))
                .set(roomRef.collection(FirestoreCollections.MODLOG).document(), modLogEntry("unban", uid, me))
                .commit().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not unban this user. Please try again."))
        }
    }

    override fun observeBans(roomCode: String): Flow<List<Ban>> = callbackFlow {
        val registration = room(roomCode).collection(FirestoreCollections.BANS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { Ban(it.id, it.getString("username").orEmpty()) })
                }
            }
        awaitClose { registration.remove() }
    }

    private fun modLogEntry(action: String, targetUid: String, actorUid: String) = mapOf(
        "action" to action,
        "targetUid" to targetUid,
        "actorUid" to actorUid,
        "at" to FieldValue.serverTimestamp()
    )

    private companion object {
        // Must match the limits in firestore.rules.
        const val MAX_REPORT_TEXT = 500
    }
}
