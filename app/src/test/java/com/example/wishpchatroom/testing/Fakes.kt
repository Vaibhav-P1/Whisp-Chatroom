package com.example.wishpchatroom.testing

import com.example.wishpchatroom.data.Ban
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.ModerationRepository
import com.example.wishpchatroom.data.Presence
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.ReportCategory
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeUserRepository : UserRepository {
    var loggedIn = false
    var user: User? = null
    var loginResult: Resource<Boolean> = Resource.Success(true)
    var signUpResult: Resource<Boolean> = Resource.Success(true)
    var gate: CompletableDeferred<Unit>? = null
    var loginCalls = 0
    var cleared = false
    var lastLogin: Pair<String, String>? = null
    var lastSignUp: List<String>? = null
    var resetResult: Resource<Unit> = Resource.Success(Unit)
    var resetEmail: String? = null
    var deleteResult: Resource<Unit> = Resource.Success(Unit)
    var deletePassword: String? = null

    override suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Resource<Boolean> {
        lastSignUp = listOf(email, password, firstName, lastName)
        gate?.await()
        if (signUpResult is Resource.Success) user = User(firstName, lastName, email)
        return signUpResult
    }

    override suspend fun login(email: String, password: String): Resource<Boolean> {
        loginCalls++
        lastLogin = email to password
        gate?.await()
        if (loginResult is Resource.Success) loggedIn = true
        return loginResult
    }

    override suspend fun sendPasswordReset(email: String): Resource<Unit> {
        resetEmail = email
        return resetResult
    }

    override suspend fun deleteAccount(password: String): Resource<Unit> {
        deletePassword = password
        if (deleteResult is Resource.Success) { loggedIn = false; user = null }
        return deleteResult
    }

    override fun isLoggedIn() = loggedIn
    override suspend fun getCurrentUser() = user
    override fun logout() { loggedIn = false; user = null }
    override suspend fun clearLocalData() { cleared = true }
}

class FakeModerationRepository : ModerationRepository {
    val blocked = MutableStateFlow<Set<String>>(emptySet())
    val bans = MutableStateFlow<List<Ban>>(emptyList())
    var blockResult: Resource<Unit> = Resource.Success(Unit)
    var reportResult: Resource<Unit> = Resource.Success(Unit)
    var removeResult: Resource<Unit> = Resource.Success(Unit)
    val reports = mutableListOf<List<Any?>>()
    val removed = mutableListOf<Pair<String, String>>()
    val unbanned = mutableListOf<String>()

    override fun observeBlockedUids(): Flow<Set<String>> = blocked

    override suspend fun blockUser(uid: String): Resource<Unit> {
        if (blockResult is Resource.Success) blocked.value = blocked.value + uid
        return blockResult
    }

    override suspend fun unblockUser(uid: String): Resource<Unit> {
        blocked.value = blocked.value - uid
        return Resource.Success(Unit)
    }

    override suspend fun reportUser(
        roomCode: String, targetUid: String, messageId: String?, messageText: String?,
        category: ReportCategory, description: String
    ): Resource<Unit> {
        reports += listOf(roomCode, targetUid, messageId, messageText, category, description)
        return reportResult
    }

    override suspend fun removeAndBan(roomCode: String, targetUid: String, targetName: String): Resource<Unit> {
        removed += targetUid to targetName
        return removeResult
    }

    override suspend fun unban(roomCode: String, uid: String): Resource<Unit> {
        unbanned += uid
        return Resource.Success(Unit)
    }

    override fun observeBans(roomCode: String): Flow<List<Ban>> = bans
}

class FakeMissionRepository : com.example.wishpchatroom.data.MissionRepository {
    val tasks = MutableStateFlow<List<com.example.wishpchatroom.data.MissionTask>>(emptyList())
    val notes = MutableStateFlow<List<com.example.wishpchatroom.data.SavedNote>>(emptyList())
    var result: Resource<Unit> = Resource.Success(Unit)
    val added = mutableListOf<Pair<String, Long>>()
    val toggled = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<String>()
    val announcements = mutableListOf<String>()
    val outcomes = mutableListOf<String>()
    val saved = mutableListOf<List<String>>()
    val deletedNotes = mutableListOf<String>()

    override fun observeTasks(roomCode: String) = tasks

    override suspend fun addTask(roomCode: String, text: String, order: Long): Resource<Unit> {
        added += text to order
        return result
    }

    override suspend fun setTaskDone(roomCode: String, taskId: String, done: Boolean): Resource<Unit> {
        toggled += taskId to done
        return result
    }

    override suspend fun deleteTask(roomCode: String, taskId: String): Resource<Unit> {
        deleted += taskId
        return result
    }

    override suspend fun setAnnouncement(roomCode: String, text: String): Resource<Unit> {
        announcements += text
        return result
    }

    override suspend fun setOutcome(roomCode: String, text: String): Resource<Unit> {
        outcomes += text
        return result
    }

    override suspend fun saveNote(roomTitle: String, author: String, text: String, source: String): Resource<Unit> {
        saved += listOf(roomTitle, author, text, source)
        return result
    }

    override fun observeSavedNotes() = notes

    override suspend fun deleteNote(noteId: String): Resource<Unit> {
        deletedNotes += noteId
        return result
    }
}

class FakeContactsRepository : com.example.wishpchatroom.data.ContactsRepository {
    override var currentUid: String? = "me"

    val handle = MutableStateFlow<String?>(null)
    val incoming = MutableStateFlow<List<com.example.wishpchatroom.data.FriendRequest>>(emptyList())
    val outgoing = MutableStateFlow<List<com.example.wishpchatroom.data.FriendRequest>>(emptyList())
    val friends = MutableStateFlow<List<com.example.wishpchatroom.data.Friend>>(emptyList())
    val inbox = MutableStateFlow<List<com.example.wishpchatroom.data.InboxItem>>(emptyList())
    var claimResult: Resource<Unit> = Resource.Success(Unit)
    var requestResult: Resource<Unit> = Resource.Success(Unit)
    var acceptResult: Resource<String> = Resource.Success("dm_a_b")
    var removeFriendResult: Resource<Unit> = Resource.Success(Unit)
    val claimed = mutableListOf<String>()
    val requested = mutableListOf<String>()
    val accepted = mutableListOf<com.example.wishpchatroom.data.FriendRequest>()
    val removedRequests = mutableListOf<com.example.wishpchatroom.data.FriendRequest>()
    val removedFriends = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val pins = mutableListOf<Pair<String, Boolean>>()
    val mutes = mutableListOf<Pair<String, Boolean>>()
    val archives = mutableListOf<Pair<String, Boolean>>()

    override fun observeMyHandle(): Flow<String?> = handle

    override suspend fun claimHandle(handle: String): Resource<Unit> {
        claimed += handle
        return claimResult
    }

    override suspend fun findByHandle(handle: String): Resource<com.example.wishpchatroom.data.HandleInfo?> =
        Resource.Success(null)

    override suspend fun sendRequest(handle: String): Resource<Unit> {
        requested += handle
        return requestResult
    }

    override fun observeIncomingRequests() = incoming
    override fun observeOutgoingRequests() = outgoing

    override suspend fun acceptRequest(request: com.example.wishpchatroom.data.FriendRequest): Resource<String> {
        accepted += request
        return acceptResult
    }

    override suspend fun removeRequest(request: com.example.wishpchatroom.data.FriendRequest): Resource<Unit> {
        removedRequests += request
        return Resource.Success(Unit)
    }

    override fun observeFriends() = friends

    override suspend fun removeFriend(friendUid: String): Resource<Unit> {
        removedFriends += friendUid
        return removeFriendResult
    }

    override fun observeInbox() = inbox

    override suspend fun setPinned(roomCode: String, pinned: Boolean): Resource<Unit> {
        pins += roomCode to pinned
        return Resource.Success(Unit)
    }

    override suspend fun setMuted(roomCode: String, muted: Boolean): Resource<Unit> {
        mutes += roomCode to muted
        return Resource.Success(Unit)
    }

    override suspend fun setArchived(roomCode: String, archived: Boolean): Resource<Unit> {
        archives += roomCode to archived
        return Resource.Success(Unit)
    }

    override suspend fun markRead(roomCode: String) { reads += roomCode }
}

class FakeChatRepository : ChatRepository {
    override var currentUid: String? = "me"

    var now: Long = System.currentTimeMillis()
    override fun serverNow(): Long = now
    val revealed = mutableListOf<String>()
    var revealResult: Resource<Unit> = Resource.Success(Unit)
    var purgeCalls = 0

    val room = MutableStateFlow<Room?>(null)
    val presence = MutableStateFlow<List<Presence>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())

    var sent = mutableListOf<Triple<String, String, ReplyRef?>>()
    val sentTtls = mutableListOf<Long?>()
    var edits = mutableListOf<Pair<String, String>>()
    var sendResult: Resource<Unit> = Resource.Success(Unit)
    var leaveResult: Resource<Unit> = Resource.Success(Unit)
    var deleteRoomResult: Resource<Unit> = Resource.Success(Unit)
    var cachedOlder = false
    var serverOlderResult: Resource<Boolean> = Resource.Success(true)
    var fetchOlderCalls = 0
    var forgotten = false
    var tempDeleteChecked = false
    val presenceWrites = mutableListOf<Boolean>()

    override fun observeMessages(roomCode: String, limit: Int): Flow<List<Message>> = messages
    override fun searchMessages(roomCode: String, query: String): Flow<List<Message>> =
        flowOf(messages.value.filter { it.messageText.contains(query, ignoreCase = true) })

    override suspend fun syncMessages(roomCode: String) {}

    override suspend fun sendMessage(
        roomCode: String,
        messageText: String,
        username: String,
        replyTo: ReplyRef?,
        whisperTtlSeconds: Long?
    ): Resource<Unit> {
        sent += Triple(messageText, username, replyTo)
        sentTtls += whisperTtlSeconds
        return sendResult
    }

    override suspend fun revealWhisper(roomCode: String, messageId: String): Resource<Unit> {
        revealed += messageId
        return revealResult
    }

    override suspend fun purgeExpired(roomCode: String) { purgeCalls++ }

    override suspend fun editMessage(roomCode: String, messageId: String, newText: String): Resource<Unit> {
        edits += messageId to newText
        return Resource.Success(Unit)
    }

    override suspend fun deleteMessage(roomCode: String, messageId: String) = Resource.Success(Unit)
    override suspend fun toggleReaction(roomCode: String, messageId: String, emoji: String) = Resource.Success(Unit)
    override suspend fun retryMessage(roomCode: String, messageId: String) = Resource.Success(Unit)
    override suspend fun hasCachedOlder(roomCode: String, shownCount: Int) = cachedOlder

    override suspend fun fetchOlderMessages(roomCode: String): Resource<Boolean> {
        fetchOlderCalls++
        return serverOlderResult
    }

    override suspend fun getRoom(roomCode: String): Room? = room.value
    override fun observeRoom(roomCode: String): Flow<Room?> = room
    override fun observePresence(roomCode: String): Flow<List<Presence>> = presence
    override suspend fun leaveRoom(roomCode: String) = leaveResult
    override suspend fun forgetRoom(roomCode: String) { forgotten = true }
    override suspend fun closeRoom(roomCode: String) = Resource.Success(Unit)
    override suspend fun deleteRoom(roomCode: String) = deleteRoomResult
    override suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {
        presenceWrites += isPresent
    }
    override suspend fun deleteIfTemporaryOwner(roomCode: String) { tempDeleteChecked = true }
}
