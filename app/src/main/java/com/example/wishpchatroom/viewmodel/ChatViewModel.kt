package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
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
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(roomCode)
                .catch { e -> showError(e.toUserMessage("Could not load messages.")) }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
    }

    fun sendMessage(roomCode: String, messageText: String) {
        val username = _uiState.value.roomUsername
        if (username == null) {
            showError("Still joining the room, try again in a moment")
            return
        }
        viewModelScope.launch {
            val result = chatRepository.sendMessage(roomCode, messageText.trim(), username)
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

    // Cancelling the collection detaches the Firestore listener before the room can be deleted,
    // otherwise the listener reports a permission error.
    private fun stopListening() {
        messagesJob?.cancel()
        messagesJob = null
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }
}
