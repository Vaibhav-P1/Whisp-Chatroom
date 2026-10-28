package com.example.wishpchatroom.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreContactsRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ContactsRepository {

    override val currentUid: String? get() = auth.currentUser?.uid

    private fun userDoc(uid: String) = firestore.collection(FirestoreCollections.USERS).document(uid)
    private fun friendDoc(owner: String, friend: String) = userDoc(owner).collection(FirestoreCollections.FRIENDS).document(friend)
    private fun prefsDoc(owner: String, roomCode: String) = userDoc(owner).collection(FirestoreCollections.CHATS).document(roomCode)
    private fun handleDoc(handle: String) = firestore.collection(FirestoreCollections.HANDLES).document(handle)
    private fun requestDoc(id: String) = firestore.collection(FirestoreCollections.FRIEND_REQUESTS).document(id)
    private fun roomDoc(roomCode: String) = firestore.collection(FirestoreCollections.ROOMS).document(roomCode)

    override fun observeMyHandle(): Flow<String?> = callbackFlow {
        val me = currentUid
        if (me == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val registration = userDoc(me).addSnapshotListener { snapshot, error ->
            if (error != null) trySend(null) else trySend(snapshot?.getString("handle"))
        }
        awaitClose { registration.remove() }
    }

    override suspend fun claimHandle(handle: String): Resource<Unit> {
        val me = currentUid ?: return Resource.Error("You must be logged in")
        validateHandle(handle)?.let { return Resource.Error(it) }
        return try {
            val profile = userDoc(me).get().await()
            val old = profile.getString("handle")
            if (old == handle) return Resource.Error("That is already your handle.")
            val changedAt = profile.getTimestamp("handleChangedAt")?.toDate()?.time
            if (old != null && changedAt != null &&
                System.currentTimeMillis() - changedAt < HANDLE_CHANGE_DAYS * DAY_MS
            ) {
                return Resource.Error("You can change your handle once every $HANDLE_CHANGE_DAYS days.")
            }
            if (handleDoc(handle).get().await().exists()) return Resource.Error("That handle is taken.")

            // The handle document and the profile field are written together; the rules reject either alone.
            val batch = firestore.batch()
                .set(
                    handleDoc(handle),
                    mapOf(
                        "uid" to me,
                        "displayName" to displayNameOf(profile.getString("firstName"), profile.getString("lastName")),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                .update(userDoc(me), mapOf("handle" to handle, "handleChangedAt" to FieldValue.serverTimestamp()))
            if (old != null) batch.delete(handleDoc(old))
            batch.commit().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            // Two people claiming the same handle at once: the loser's batch is rejected by the rules.
            Resource.Error(e.toUserMessage("Could not save the handle. It may have just been taken."))
        }
    }

    override suspend fun findByHandle(handle: String): Resource<HandleInfo?> {
        val clean = handle.trim().lowercase().removePrefix("@")
        if (validateHandle(clean) != null) return Resource.Success(null)
        return try {
            val snapshot = handleDoc(clean).get().await()
            if (!snapshot.exists()) {
                Resource.Success(null)
            } else {
                Resource.Success(
                    HandleInfo(clean, snapshot.getString("uid").orEmpty(), snapshot.getString("displayName").orEmpty())
                )
            }
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not look that handle up."))
        }
    }

    override suspend fun sendRequest(handle: String): Resource<Unit> {
        val me = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            val profile = userDoc(me).get().await()
            val myHandle = profile.getString("handle")
                ?: return Resource.Error("Claim your own handle first, so people can recognise your request.")
            val target = when (val found = findByHandle(handle)) {
                is Resource.Error -> return found
                is Resource.Success -> found.data ?: return Resource.Error("No user has that handle.")
            }
            if (target.uid == me) return Resource.Error("That is your own handle.")

            requestDoc("${me}_${target.uid}").set(
                mapOf(
                    "from" to me,
                    "to" to target.uid,
                    "fromHandle" to myHandle,
                    "fromName" to displayNameOf(profile.getString("firstName"), profile.getString("lastName")),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            // Deliberately vague: it could be a block, a duplicate or an existing friendship.
            Resource.Error(e.toUserMessage("Couldn't send request."))
        }
    }

    override fun observeIncomingRequests(): Flow<List<FriendRequest>> = observeRequests("to")

    override fun observeOutgoingRequests(): Flow<List<FriendRequest>> = observeRequests("from")

    private fun observeRequests(field: String): Flow<List<FriendRequest>> = callbackFlow {
        val me = currentUid
        if (me == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = firestore.collection(FirestoreCollections.FRIEND_REQUESTS)
            .whereEqualTo(field, me)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map {
                        FriendRequest(
                            id = it.id,
                            from = it.getString("from").orEmpty(),
                            to = it.getString("to").orEmpty(),
                            fromHandle = it.getString("fromHandle").orEmpty(),
                            fromName = it.getString("fromName").orEmpty()
                        )
                    })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun acceptRequest(request: FriendRequest): Resource<String> {
        val me = currentUid ?: return Resource.Error("You must be logged in")
        if (request.to != me) return Resource.Error("This request is not for you.")
        val other = request.from
        val dmId = dmRoomId(me, other)
        return try {
            val profile = userDoc(me).get().await()
            val myHandle = profile.getString("handle").orEmpty()
            val myName = fullNameOf(profile.getString("firstName"), profile.getString("lastName"))

            fun friendBatch() = firestore.batch()
                .set(friendDoc(me, other), mapOf("since" to FieldValue.serverTimestamp(), "handle" to request.fromHandle))
                .set(friendDoc(other, me), mapOf("since" to FieldValue.serverTimestamp(), "handle" to myHandle))
                .delete(requestDoc(request.id))

            try {
                // First time: the DM is created with the friendship, so no second DM can ever exist.
                friendBatch().set(
                    roomDoc(dmId),
                    mapOf(
                        "roomCode" to dmId,
                        "creatorUid" to me,
                        "roomOpen" to true,
                        "members" to mapOf(me to myName, other to request.fromName),
                        "temporary" to false,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "type" to "dm",
                        "locked" to false
                    )
                ).commit().await()
            } catch (e: Exception) {
                // The DM already exists (these two were friends before): unlock it instead.
                friendBatch().update(roomDoc(dmId), mapOf("locked" to false, "lockedBy" to "")).commit().await()
            }
            Resource.Success(dmId)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not accept the request. Please try again."))
        }
    }

    override suspend fun removeRequest(request: FriendRequest): Resource<Unit> =
        try {
            requestDoc(request.id).delete().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not remove the request. Please try again."))
        }

    override fun observeFriends(): Flow<List<Friend>> = callbackFlow {
        val me = currentUid
        if (me == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = userDoc(me).collection(FirestoreCollections.FRIENDS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { Friend(it.id, it.getString("handle").orEmpty()) })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun removeFriend(friendUid: String): Resource<Unit> {
        val me = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            // Unfriending locks the DM exactly like blocking does, so the other side cannot tell which it was.
            firestore.batch()
                .delete(friendDoc(me, friendUid))
                .delete(friendDoc(friendUid, me))
                .update(roomDoc(dmRoomId(me, friendUid)), mapOf("locked" to true, "lockedBy" to me))
                .commit().await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not remove this friend. Please try again."))
        }
    }

    // --- inbox ---

    private fun observePrefs(): Flow<Map<String, ChatPrefs>> = callbackFlow {
        val me = currentUid
        if (me == null) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }
        val registration = userDoc(me).collection(FirestoreCollections.CHATS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyMap())
                } else if (snapshot != null) {
                    trySend(snapshot.documents.associate {
                        it.id to ChatPrefs(
                            pinned = it.getBoolean("pinned") ?: false,
                            muted = it.getBoolean("muted") ?: false,
                            archived = it.getBoolean("archived") ?: false,
                            archivedAt = it.getTimestamp("archivedAt")?.toDate()?.time,
                            lastReadAt = it.getTimestamp("lastReadAt")?.toDate()?.time
                        )
                    })
                }
            }
        awaitClose { registration.remove() }
    }

    private fun observeRoom(roomCode: String): Flow<Room?> = callbackFlow {
        val registration = roomDoc(roomCode).addSnapshotListener { snapshot, error ->
            if (error != null) trySend(null) else trySend(snapshot?.toObject(Room::class.java))
        }
        awaitClose { registration.remove() }
    }

    override fun observeInbox(): Flow<List<InboxItem>> {
        val me = currentUid ?: return flowOf(emptyList())
        return observeFriends().flatMapLatest { allFriends ->
            // One document listener per conversation, only while the inbox is collected. Rooms cannot
            // be listed by rules, so whereIn queries are not possible.
            val friends = allFriends.take(MAX_INBOX)
            val rooms: Flow<List<Pair<Friend, Room?>>> =
                if (friends.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(friends.map { friend ->
                        observeRoom(dmRoomId(me, friend.uid)).map { friend to it }
                    }) { it.toList() }
                }
            combine(rooms, observePrefs()) { pairs, prefs ->
                sortInbox(pairs.map { (friend, room) -> toInboxItem(me, friend, room, prefs[dmRoomId(me, friend.uid)]) })
            }
        }
    }

    private fun toInboxItem(me: String, friend: Friend, room: Room?, prefs: ChatPrefs?): InboxItem {
        val roomCode = dmRoomId(me, friend.uid)
        val last = room?.lastMessage
        val lastAt = room?.lastMessageAt?.time
        val readAt = prefs?.lastReadAt ?: 0L
        return InboxItem(
            roomCode = roomCode,
            friendUid = friend.uid,
            friendName = room?.members?.get(friend.uid) ?: friend.handle,
            friendHandle = friend.handle,
            lastText = last?.text,
            lastSenderUid = last?.uid,
            lastAt = lastAt,
            unread = lastAt != null && lastAt > readAt && last?.uid != me,
            pinned = prefs?.pinned ?: false,
            muted = prefs?.muted ?: false,
            // An archived conversation comes back when a newer message arrives.
            archived = (prefs?.archived ?: false) && !(lastAt != null && lastAt > (prefs?.archivedAt ?: 0L)),
            locked = room?.locked ?: false
        )
    }

    private suspend fun writePrefs(roomCode: String, fields: Map<String, Any>): Resource<Unit> {
        val me = currentUid ?: return Resource.Error("You must be logged in")
        return try {
            prefsDoc(me, roomCode).set(fields, SetOptions.merge()).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not save that change. Please try again."))
        }
    }

    override suspend fun setPinned(roomCode: String, pinned: Boolean) = writePrefs(roomCode, mapOf("pinned" to pinned))

    override suspend fun setMuted(roomCode: String, muted: Boolean) = writePrefs(roomCode, mapOf("muted" to muted))

    override suspend fun setArchived(roomCode: String, archived: Boolean) =
        writePrefs(roomCode, mapOf("archived" to archived, "archivedAt" to FieldValue.serverTimestamp()))

    override suspend fun markRead(roomCode: String) {
        writePrefs(roomCode, mapOf("lastReadAt" to FieldValue.serverTimestamp()))
    }

    private fun fullNameOf(first: String?, last: String?) =
        "${first.orEmpty()} ${last.orEmpty()}".trim().ifEmpty { "User" }.take(50)

    // First name and last initial: enough to recognise a friend without exposing the full name.
    private fun displayNameOf(first: String?, last: String?): String {
        val initial = last?.trim()?.firstOrNull()?.let { " $it." }.orEmpty()
        return "${first.orEmpty().trim()}$initial".trim().ifEmpty { "User" }.take(50)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_INBOX = 30
    }
}
