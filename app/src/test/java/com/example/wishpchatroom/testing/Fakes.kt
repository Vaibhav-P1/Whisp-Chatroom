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

class FakeChatRepository : ChatRepository {
    override var currentUid: String? = "me"

    val room = MutableStateFlow<Room?>(null)
    val presence = MutableStateFlow<List<Presence>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())

    var sent = mutableListOf<Triple<String, String, ReplyRef?>>()
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

    override suspend fun sendMessage(roomCode: String, messageText: String, username: String, replyTo: ReplyRef?): Resource<Unit> {
        sent += Triple(messageText, username, replyTo)
        return sendResult
    }

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
