package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Ban
import com.example.wishpchatroom.data.ContactsRepository
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Mission
import com.example.wishpchatroom.data.MissionPhase
import com.example.wishpchatroom.data.ModerationRepository
import com.example.wishpchatroom.data.Presence
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.ReportCategory
import com.example.wishpchatroom.data.expiresAtMillis
import com.example.wishpchatroom.data.isDmRoom
import com.example.wishpchatroom.data.missionPhase
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// REMOVED: the owner kicked this user, so the room is no longer readable for them.
enum class RoomStatus { OPEN, CLOSED, DELETED, REMOVED }

data class Member(
    val uid: String,
    val username: String,
    val isOwner: Boolean,
    val isYou: Boolean,
    val isOnline: Boolean
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isOwner: Boolean = false,
    // The username this user chose for the current room.
    val roomUsername: String? = null,
    val errorMessage: String? = null,
    // False once the server reported that nothing older exists than what is cached.
    val hasMoreOlder: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val roomStatus: RoomStatus = RoomStatus.OPEN,
    val members: List<Member> = emptyList(),
    // What the message box is currently doing besides sending a plain message (at most one of them).
    val replyingTo: Message? = null,
    val editing: Message? = null,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    // Users this person blocked: their messages are collapsed and left out of search (on this device only).
    val blockedUids: Set<String> = emptySet(),
    // The owner's ban list, loaded while the members dialog is open.
    val bans: List<Ban> = emptyList(),
    // A short confirmation such as "Report sent", shown once.
    val infoMessage: String? = null,
    // A direct message with a friend: no room code, members list, close or delete.
    val isDm: Boolean = false,
    val dmPeerName: String? = null,
    // A locked DM keeps its history but takes no new messages (the friend was removed or blocked).
    val isLocked: Boolean = false,
    // Set for mission rooms. The expiry is the server creation time plus the mission duration.
    val mission: Mission? = null,
    val missionExpiresAt: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val moderationRepository: ModerationRepository,
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var syncJob: Job? = null
    private var roomJob: Job? = null
    private var heartbeatJob: Job? = null
    private var searchJob: Job? = null
    private var blocksJob: Job? = null
    private var cleanupJob: Job? = null
    private var bansJob: Job? = null
    private var isLeaving = false

    private val searchQuery = MutableStateFlow("")

    // How many of the newest cached messages are shown. Grows as the user scrolls back through history.
    private val messageLimit = MutableStateFlow(MESSAGE_WINDOW)

    val currentUid: String? get() = chatRepository.currentUid

    /** Starts following the room: its status (open, closed, deleted), its members and who is online. */
    fun loadRoom(roomCode: String) {
        if (isDmRoom(roomCode)) viewModelScope.launch { contactsRepository.markRead(roomCode) }
        blocksJob?.cancel()
        blocksJob = viewModelScope.launch {
            moderationRepository.observeBlockedUids()
                .catch { emit(emptySet()) }
                .collect { blocked -> _uiState.update { it.copy(blockedUids = blocked) } }
        }
        roomJob?.cancel()
        roomJob = viewModelScope.launch {
            // Presence is a nice-to-have: if its listener fails (for example because the room was just
            // deleted) the member list simply shows everyone as offline.
            val presence = chatRepository.observePresence(roomCode)
                .catch { emit(emptyList()) }
                .onStart { emit(emptyList()) }

            combine(chatRepository.observeRoom(roomCode), presence, ticker()) { room, presenceList, now ->
                applyRoom(room, presenceList, now)
            }
                .catch { e -> showError(e.toUserMessage("Could not load room details.")) }
                .collect { }
        }
    }

    private fun applyRoom(room: Room?, presence: List<Presence>, now: Long) {
        if (room == null) {
            // Not an error for the user who is deleting the room themselves.
            if (!isLeaving) _uiState.update { it.copy(roomStatus = RoomStatus.DELETED) }
            return
        }

        val uid = chatRepository.currentUid
        // Long after the mission ended the rules deny the room: treat it like a deleted room.
        if (room.missionPhase(now) == MissionPhase.GONE && !isLeaving) {
            _uiState.update { it.copy(roomStatus = RoomStatus.DELETED) }
            return
        }
        // The owner removed this user: the members map no longer has them.
        if (uid != null && uid !in room.members && !isLeaving) {
            _uiState.update { it.copy(roomStatus = RoomStatus.REMOVED) }
            return
        }
        val presenceByUid = presence.associateBy { it.uid }
        val members = room.members
            .map { (memberUid, username) ->
                val seen = presenceByUid[memberUid]
                Member(
                    uid = memberUid,
                    username = username,
                    isOwner = memberUid == room.creatorUid,
                    isYou = memberUid == uid,
                    isOnline = memberUid == uid ||
                        (seen != null && seen.isPresent && now - seen.lastSeen < ONLINE_TIMEOUT_MS)
                )
            }
            .sortedWith(
                compareByDescending<Member> { it.isYou }
                    .thenByDescending { it.isOnline }
                    .thenBy { it.username.lowercase() }
            )

        _uiState.update {
            it.copy(
                isOwner = room.creatorUid == uid,
                roomUsername = room.members[uid],
                roomStatus = if (room.roomOpen) RoomStatus.OPEN else RoomStatus.CLOSED,
                members = members,
                isDm = room.type == "dm",
                dmPeerName = room.members.entries.firstOrNull { e -> e.key != uid }?.value,
                isLocked = room.locked,
                mission = room.mission,
                missionExpiresAt = room.expiresAtMillis()
            )
        }
    }

    fun loadMessages(roomCode: String) {
        messagesJob?.cancel()
        syncJob?.cancel()
        // The UI reads the local cache; the sync job keeps that cache up to date from the server.
        messagesJob = viewModelScope.launch {
            messageLimit
                .flatMapLatest { limit -> chatRepository.observeMessages(roomCode, limit) }
                .catch { e -> showError(e.toUserMessage("Could not load messages.")) }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
        // Hidden whispers are removed from the cache straight away and from the server as a best effort.
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            while (true) {
                chatRepository.purgeExpired(roomCode)
                delay(CLEANUP_INTERVAL_MS)
            }
        }
        syncJob = viewModelScope.launch {
            try {
                chatRepository.syncMessages(roomCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Expected when the room was just deleted; the room status already tells the user.
                if (_uiState.value.roomStatus !in TERMINAL_STATUSES && !isLeaving) {
                    showError(e.toUserMessage("Could not load messages."))
                }
            }
        }
    }

    /** Called when the user scrolls to the top of the chat: reveals one more page of older messages. */
    fun loadOlderMessages(roomCode: String) {
        if (_uiState.value.isLoadingOlder) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOlder = true) }
            try {
                when {
                    // History already on the device: just show more of it, no network needed.
                    chatRepository.hasCachedOlder(roomCode, messageLimit.value) -> showMoreCached()
                    // Cache exhausted: ask the server, unless it already said there is nothing older.
                    _uiState.value.hasMoreOlder -> fetchOlderFromServer(roomCode)
                }
            } finally {
                _uiState.update { it.copy(isLoadingOlder = false) }
            }
        }
    }

    private fun showMoreCached() {
        messageLimit.update { it + PAGE_SIZE }
    }

    private suspend fun fetchOlderFromServer(roomCode: String) {
        when (val result = chatRepository.fetchOlderMessages(roomCode)) {
            is Resource.Success -> {
                showMoreCached()
                _uiState.update { it.copy(hasMoreOlder = result.data) }
            }
            is Resource.Error -> showError(result.message)
        }
    }

    /**
     * Sends the text as a new message, as a reply, or as an edit of an existing message, depending on
     * what the message box is doing. Returns false if it was not accepted, so the caller can keep
     * what the user typed.
     */
    fun sendMessage(roomCode: String, messageText: String, whisperTtlSeconds: Long? = null): Boolean {
        val username = _uiState.value.roomUsername
        if (username == null) {
            showError("Still joining the room, try again in a moment")
            return false
        }
        val text = messageText.trim()
        if (text.length > MAX_MESSAGE_LENGTH) {
            showError("Message is too long (max $MAX_MESSAGE_LENGTH characters)")
            return false
        }

        val state = _uiState.value
        val editing = state.editing
        if (editing != null) {
            _uiState.update { it.copy(editing = null) }
            // Saving an unchanged message is not an edit.
            if (text != editing.messageText) {
                viewModelScope.launch {
                    val result = chatRepository.editMessage(roomCode, editing.id, text)
                    if (result is Resource.Error) showError(result.message)
                }
            }
            return true
        }

        // A whisper cannot quote anything, so a pending reply is dropped when sending one.
        val replyTo = if (whisperTtlSeconds != null) null else state.replyingTo?.let { quoted ->
            ReplyRef(quoted.id, quoted.username, quoted.messageText.take(REPLY_SNIPPET_LENGTH))
        }
        _uiState.update { it.copy(replyingTo = null) }
        viewModelScope.launch {
            val result = chatRepository.sendMessage(roomCode, text, username, replyTo, whisperTtlSeconds)
            if (result is Resource.Error) showError(result.message)
        }
        return true
    }

    /** Opens search over the messages stored on this device. */
    fun startSearch(roomCode: String) {
        _uiState.update { it.copy(isSearching = true) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .flatMapLatest { query ->
                    if (query.isBlank()) flowOf(emptyList()) else chatRepository.searchMessages(roomCode, query)
                }
                .catch { e -> showError(e.toUserMessage("Search failed.")) }
                .collect { results -> _uiState.update { it.copy(searchResults = results) } }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        searchQuery.value = ""
        _uiState.update { it.copy(isSearching = false, searchQuery = "", searchResults = emptyList()) }
    }

    fun startReply(message: Message) {
        _uiState.update { it.copy(replyingTo = message, editing = null) }
    }

    fun startEdit(message: Message) {
        _uiState.update { it.copy(editing = message, replyingTo = null) }
    }

    fun cancelComposerMode() {
        _uiState.update { it.copy(replyingTo = null, editing = null) }
    }

    fun deleteMessage(roomCode: String, messageId: String) {
        viewModelScope.launch {
            val result = chatRepository.deleteMessage(roomCode, messageId)
            if (result is Resource.Error) showError(result.message)
        }
    }

    /** The server's time as best known, for countdowns and for hiding expired whispers. */
    fun serverNow(): Long = chatRepository.serverNow()

    /** Opens a sealed whisper: this starts its timer for everyone in the room. */
    fun revealWhisper(roomCode: String, message: Message) {
        if (!message.isSealedFor(chatRepository.currentUid)) return
        viewModelScope.launch {
            val result = chatRepository.revealWhisper(roomCode, message.id)
            if (result is Resource.Error) showError(result.message)
        }
    }

    fun toggleReaction(roomCode: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            val result = chatRepository.toggleReaction(roomCode, messageId, emoji)
            if (result is Resource.Error) showError(result.message)
        }
    }

    /** Sends a message that failed earlier. It keeps its id, so it cannot be delivered twice. */
    fun retryMessage(roomCode: String, messageId: String) {
        viewModelScope.launch {
            val result = chatRepository.retryMessage(roomCode, messageId)
            if (result is Resource.Error) showError(result.message)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun blockUser(uid: String) {
        viewModelScope.launch {
            when (val result = moderationRepository.blockUser(uid)) {
                is Resource.Success -> _uiState.update { it.copy(infoMessage = "User blocked. Their messages are hidden on this device.") }
                is Resource.Error -> showError(result.message)
            }
        }
    }

    fun unblockUser(uid: String) {
        viewModelScope.launch {
            val result = moderationRepository.unblockUser(uid)
            if (result is Resource.Error) showError(result.message)
        }
    }

    fun reportMessage(roomCode: String, message: Message, category: ReportCategory, description: String) {
        reportUser(roomCode, message.uid, message.id, message.messageText, category, description)
    }

    fun reportUser(
        roomCode: String,
        targetUid: String,
        messageId: String?,
        messageText: String?,
        category: ReportCategory,
        description: String
    ) {
        viewModelScope.launch {
            val result = moderationRepository.reportUser(
                roomCode, targetUid, messageId, messageText, category, description
            )
            when (result) {
                is Resource.Success -> _uiState.update { it.copy(infoMessage = "Report sent. Thank you.") }
                is Resource.Error -> showError(result.message)
            }
        }
    }

    /** Owner only. Removes the member, bans them from rejoining and logs it in one batch. */
    fun removeAndBan(roomCode: String, member: Member) {
        if (!_uiState.value.isOwner || member.isYou) return
        viewModelScope.launch {
            val result = moderationRepository.removeAndBan(roomCode, member.uid, member.username)
            if (result is Resource.Error) showError(result.message)
        }
    }

    fun unban(roomCode: String, ban: Ban) {
        viewModelScope.launch {
            val result = moderationRepository.unban(roomCode, ban.uid)
            if (result is Resource.Error) showError(result.message)
        }
    }

    /** Owner only: follow the ban list while the members dialog is open. */
    fun startWatchingBans(roomCode: String) {
        if (!_uiState.value.isOwner) return
        bansJob?.cancel()
        bansJob = viewModelScope.launch {
            moderationRepository.observeBans(roomCode)
                .catch { emit(emptyList()) }
                .collect { bans -> _uiState.update { it.copy(bans = bans) } }
        }
    }

    fun stopWatchingBans() {
        bansJob?.cancel()
        bansJob = null
        _uiState.update { it.copy(bans = emptyList()) }
    }

    /** The owner removed this user: forget the room on this device and go back. */
    fun acknowledgeRemoved(roomCode: String, onDone: () -> Unit) = acknowledgeRoomDeleted(roomCode, onDone)

    /** Closes the room, then leaves it. A temporary room is deleted when its creator leaves. */
    fun closeRoom(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = chatRepository.closeRoom(roomCode)
            if (result is Resource.Error) {
                showError(result.message)
                return@launch
            }
            leaveRoomInternal(roomCode, onDone)
        }
    }

    fun deleteRoom(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            isLeaving = true
            stopListening()
            val result = chatRepository.deleteRoom(roomCode)
            if (result is Resource.Error) {
                // Deleting failed, so the user is still in the room: resume following it.
                isLeaving = false
                showError(result.message)
                loadRoom(roomCode)
                loadMessages(roomCode)
                return@launch
            }
            onDone()
        }
    }

    /** Explicit leave (back arrow or system back). Deletes a temporary room the user created. */
    fun leaveRoom(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch { leaveRoomInternal(roomCode, onDone) }
    }

    /** Leaves the room for good: the username is freed and the room is forgotten on this device. */
    fun leaveRoomPermanently(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (isLeaving) return@launch
            isLeaving = true
            stopListening()
            when (val result = chatRepository.leaveRoom(roomCode)) {
                is Resource.Success -> onDone()
                is Resource.Error -> {
                    isLeaving = false
                    showError(result.message)
                    loadRoom(roomCode)
                    loadMessages(roomCode)
                }
            }
        }
    }

    /** The owner deleted the room while the user was in it: forget it and go back. */
    fun acknowledgeRoomDeleted(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            isLeaving = true
            stopListening()
            chatRepository.forgetRoom(roomCode)
            onDone()
        }
    }

    private suspend fun leaveRoomInternal(roomCode: String, onDone: () -> Unit) {
        if (isLeaving) return
        isLeaving = true
        if (isDmRoom(roomCode)) contactsRepository.markRead(roomCode)
        stopListening()
        updatePresenceInternal(roomCode, false)
        chatRepository.deleteIfTemporaryOwner(roomCode)
        onDone()
    }

    /**
     * Follows the app lifecycle. While the chat is in the foreground a heartbeat refreshes the
     * presence every couple of minutes (members count as offline when it stops).
     */
    fun updateUserPresence(roomCode: String, isPresent: Boolean) {
        // Popping the screen fires lifecycle events after the room may already be deleted.
        if (isLeaving) return
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            updatePresenceInternal(roomCode, isPresent)
            while (isPresent) {
                delay(HEARTBEAT_MS)
                updatePresenceInternal(roomCode, true)
            }
        }
    }

    private suspend fun updatePresenceInternal(roomCode: String, isPresent: Boolean) {
        val username = _uiState.value.roomUsername
            ?: chatRepository.getRoom(roomCode)?.members?.get(chatRepository.currentUid)
            ?: return
        chatRepository.updateUserPresence(roomCode, username, isPresent)
    }

    // Cancelling the listeners before the room can be deleted avoids permission errors from
    // listeners whose room just disappeared.
    private fun stopListening() {
        messagesJob?.cancel()
        syncJob?.cancel()
        roomJob?.cancel()
        heartbeatJob?.cancel()
        searchJob?.cancel()
        blocksJob?.cancel()
        bansJob?.cancel()
        cleanupJob?.cancel()
        cleanupJob = null
        blocksJob = null
        bansJob = null
        messagesJob = null
        syncJob = null
        roomJob = null
        heartbeatJob = null
        searchJob = null
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    // Emits the current time now and every few seconds, so "online" decays without new presence data.
    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(chatRepository.serverNow())
            delay(TICK_MS)
        }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(RoomStatus.DELETED, RoomStatus.REMOVED)

        // How many of the newest cached messages the chat shows.
        const val MESSAGE_WINDOW = 100

        // How many more messages are revealed each time the user scrolls back to the top.
        const val PAGE_SIZE = 30

        // Must match the limit enforced in firestore.rules.
        const val MAX_MESSAGE_LENGTH = 2000

        // How much of the quoted message is stored with a reply (the rules allow up to 200).
        const val REPLY_SNIPPET_LENGTH = 100

        // Wait for the user to pause typing before running a search.
        const val SEARCH_DEBOUNCE_MS = 250L

        // A foreground chat refreshes presence this often; a member is online if seen within the timeout.
        // Every heartbeat write is also one read for each other member's presence listener, so on the
        // free Firestore tier the interval is kept long. Going to the background or leaving updates
        // presence immediately, so the timeout only matters when the app is killed.
        const val HEARTBEAT_MS = 120_000L
        const val ONLINE_TIMEOUT_MS = 300_000L
        const val TICK_MS = 30_000L

        // How often hidden whispers are cleaned up while a chat is open.
        const val CLEANUP_INTERVAL_MS = 30_000L
    }
}
