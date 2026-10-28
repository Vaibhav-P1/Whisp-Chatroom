package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
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
class FirestoreMissionRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : MissionRepository {

    private fun roomDoc(roomCode: String) = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)
    private fun tasks(roomCode: String) = roomDoc(roomCode).collection(FirestoreCollections.TASKS)
    private fun notes(uid: String) = firestore.collection(FirestoreCollections.USERS).document(uid)
        .collection(FirestoreCollections.SAVED)

    override fun observeTasks(roomCode: String): Flow<List<MissionTask>> = callbackFlow {
        val registration = tasks(roomCode).orderBy("order", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                // The checklist is secondary: if it cannot be read the chat still works.
                if (error != null) {
                    trySend(emptyList())
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map {
                        MissionTask(
                            id = it.id,
                            text = it.getString("text").orEmpty(),
                            done = it.getBoolean("done") ?: false,
                            doneBy = it.getString("doneBy").orEmpty(),
                            order = it.getLong("order") ?: 0L
                        )
                    })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun addTask(roomCode: String, text: String, order: Long): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        val clean = text.trim()
        if (clean.isEmpty()) return Resource.Error("Write the task first.")
        if (clean.length > MAX_TASK_TEXT) return Resource.Error("A task can have at most $MAX_TASK_TEXT characters.")
        return write("Could not add the task. The mission may have ended.") {
            tasks(roomCode).add(
                mapOf("text" to clean, "done" to false, "doneBy" to "", "createdBy" to me, "order" to order)
            ).await()
        }
    }

    override suspend fun setTaskDone(roomCode: String, taskId: String, done: Boolean): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        return write("Could not update the task. The mission may have ended.") {
            tasks(roomCode).document(taskId).update(mapOf("done" to done, "doneBy" to if (done) me else "")).await()
        }
    }

    override suspend fun deleteTask(roomCode: String, taskId: String): Resource<Unit> =
        write("Could not delete the task.") { tasks(roomCode).document(taskId).delete().await() }

    override suspend fun setAnnouncement(roomCode: String, text: String): Resource<Unit> {
        if (text.length > MAX_ANNOUNCEMENT) return Resource.Error("The announcement can have at most $MAX_ANNOUNCEMENT characters.")
        return write("Could not post the announcement. The mission may have ended.") {
            roomDoc(roomCode).update("mission.announcement", text.trim()).await()
        }
    }

    override suspend fun setOutcome(roomCode: String, text: String): Resource<Unit> {
        if (text.length > MAX_OUTCOME) return Resource.Error("The outcome can have at most $MAX_OUTCOME characters.")
        return write("Could not save the outcome.") {
            roomDoc(roomCode).update("mission.outcome", text.trim()).await()
        }
    }

    override suspend fun saveNote(roomTitle: String, author: String, text: String, source: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        if (text.isBlank()) return Resource.Error("There is nothing to save.")
        return write("Could not save the note. Please try again.") {
            notes(me).add(
                mapOf(
                    "roomTitle" to roomTitle.take(100),
                    "author" to author.take(50),
                    "text" to text.take(2000),
                    "source" to source,
                    "savedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    override fun observeSavedNotes(): Flow<List<SavedNote>> = callbackFlow {
        val me = auth.currentUser?.uid
        if (me == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = notes(me).orderBy("savedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map {
                        SavedNote(
                            id = it.id,
                            roomTitle = it.getString("roomTitle").orEmpty(),
                            author = it.getString("author").orEmpty(),
                            text = it.getString("text").orEmpty(),
                            source = it.getString("source").orEmpty(),
                            savedAt = it.getTimestamp("savedAt")?.toDate()?.time
                        )
                    })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun deleteNote(noteId: String): Resource<Unit> {
        val me = auth.currentUser?.uid ?: return Resource.Error("You must be logged in")
        return write("Could not delete the note.") { notes(me).document(noteId).delete().await() }
    }

    private suspend fun write(failure: String, block: suspend () -> Unit): Resource<Unit> =
        try {
            block()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage(failure))
        }
}
