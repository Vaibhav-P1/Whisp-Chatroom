package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isOwner: Boolean = false,
    // The username this user chose for the current room.
    val roomUsername: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var syncJob: Job? = null
    private var isLeaving = false

    val currentUid: String? get() = chatRepository.currentUid

    fun loadRoom(roomCode: String) {
        viewModelScope.launch {
            val room = chatRepository.getRoom(roomCode) ?: return@launch
            _uiState.update {
                it.copy(
                    isOwner = room.creatorUid == chatRepository.currentUid,
                    roomUsername = room.members[chatRepository.currentUid]
                )
            }
        }
    }

    fun loadMessages(roomCode: String) {
        stopListening()
        // The UI reads the local cache; the sync job keeps that cache up to date from the server.
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(roomCode, MESSAGE_WINDOW)
                .catch { e -> showError(e.toUserMessage("Could not load messages.")) }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
        syncJob = viewModelScope.launch {
            try {
                chatRepository.syncMessages(roomCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.toUserMessage("Could not load messages."))
            }
        }
    }

    /** Returns false if the message was not accepted, so the caller can keep what the user typed. */
    fun sendMessage(roomCode: String, messageText: String): Boolean {
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
        viewModelScope.launch {
            val result = chatRepository.sendMessage(roomCode, text, username)
            if (result is Resource.Error) showError(result.message)
        }
        return true
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
            stopListening()
            val result = chatRepository.deleteRoom(roomCode)
            if (result is Resource.Error) {
                showError(result.message)
                return@launch
            }
            onDone()
        }
    }

    /** Explicit leave (back arrow or system back). Deletes a temporary room the user created. */
    fun leaveRoom(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch { leaveRoomInternal(roomCode, onDone) }
    }

    private suspend fun leaveRoomInternal(roomCode: String, onDone: () -> Unit) {
        if (isLeaving) return
        isLeaving = true
        stopListening()
        updatePresenceInternal(roomCode, false)
        chatRepository.deleteIfTemporaryOwner(roomCode)
        onDone()
    }

    fun updateUserPresence(roomCode: String, isPresent: Boolean) {
        // Popping the screen fires lifecycle events after the room may already be deleted.
        if (isLeaving) return
        viewModelScope.launch { updatePresenceInternal(roomCode, isPresent) }
    }

    private suspend fun updatePresenceInternal(roomCode: String, isPresent: Boolean) {
        val username = _uiState.value.roomUsername
            ?: chatRepository.getRoom(roomCode)?.members?.get(chatRepository.currentUid)
            ?: return
        chatRepository.updateUserPresence(roomCode, username, isPresent)
    }

    // Cancelling the sync detaches the Firestore listener before the room can be deleted,
    // otherwise the listener reports a permission error.
    private fun stopListening() {
        messagesJob?.cancel()
        syncJob?.cancel()
        messagesJob = null
        syncJob = null
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private companion object {
        // How many of the newest cached messages the chat shows.
        const val MESSAGE_WINDOW = 100

        // Must match the limit enforced in firestore.rules.
        const val MAX_MESSAGE_LENGTH = 2000
    }
}
