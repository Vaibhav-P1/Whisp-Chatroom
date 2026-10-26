package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Resource
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private var messagesListener: ListenerRegistration? = null
    private var isLeaving = false

    private val _messages = MutableLiveData<List<Message>>()
    val messages: LiveData<List<Message>> get() = _messages

    private val _isOwner = MutableLiveData(false)
    val isOwner: LiveData<Boolean> get() = _isOwner

    // The username this user chose for the current room.
    private val _roomUsername = MutableLiveData<String?>()
    val roomUsername: LiveData<String?> get() = _roomUsername

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    val currentUid: String? get() = chatRepository.currentUid

    fun loadRoom(roomCode: String) {
        viewModelScope.launch {
            val room = chatRepository.getRoom(roomCode) ?: return@launch
            _isOwner.value = room.creatorUid == chatRepository.currentUid
            _roomUsername.value = room.members[chatRepository.currentUid]
        }
    }

    fun loadMessages(roomCode: String) {
        stopListening()
        messagesListener = chatRepository.listenToMessages(
            roomCode,
            onMessagesChanged = { messageList -> _messages.value = messageList },
            onError = { _error.value = "Could not load messages: ${it.message}" }
        )
    }

    fun sendMessage(roomCode: String, messageText: String) {
        val username = _roomUsername.value
        if (username == null) {
            _error.value = "Still joining the room, try again in a moment"
            return
        }
        viewModelScope.launch {
            val result = chatRepository.sendMessage(roomCode, messageText.trim(), username)
            if (result is Resource.Error) {
                _error.value = result.message
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Closes the room, then leaves it. A temporary room is deleted when its creator leaves. */
    fun closeRoom(roomCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = chatRepository.closeRoom(roomCode)
            if (result is Resource.Error) {
                _error.value = result.message
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
                _error.value = result.message
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
        val username = _roomUsername.value
            ?: chatRepository.getRoom(roomCode)?.members?.get(chatRepository.currentUid)
            ?: return
        chatRepository.updateUserPresence(roomCode, username, isPresent)
    }

    // Detach before the room can be deleted, otherwise the listener reports a permission error.
    private fun stopListening() {
        messagesListener?.remove()
        messagesListener = null
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }
}
