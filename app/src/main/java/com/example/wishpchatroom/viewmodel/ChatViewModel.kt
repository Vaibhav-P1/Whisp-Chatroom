package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.Injection
import com.example.wishpchatroom.data.Message
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val chatRepository = ChatRepository(Injection.instance(), FirebaseAuth.getInstance())

    private val _messages = MutableLiveData<List<Message>>()
    val messages: LiveData<List<Message>> get() = _messages

    fun loadMessages(roomCode: String) {
        chatRepository.getMessages(roomCode) { messageList ->
            _messages.value = messageList
        }
    }

    fun sendMessage(roomCode: String, messageText: String, username: String) {
        viewModelScope.launch {
            chatRepository.sendMessage(roomCode, messageText, username)
        }
    }

    fun checkRoomOwnership(roomCode: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            chatRepository.checkRoomOwnership(roomCode, callback)
        }
    }

    fun closeRoom(roomCode: String) {
        viewModelScope.launch {
            chatRepository.closeRoom(roomCode)
        }
    }

    fun deleteRoom(roomCode: String) {
        viewModelScope.launch {
            chatRepository.deleteRoom(roomCode)
        }
    }

    fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {
        viewModelScope.launch {
            chatRepository.updateUserPresence(roomCode, username, isPresent)
        }
    }

    fun checkAndDeleteTemporaryRoom(roomCode: String) {
        viewModelScope.launch {
            chatRepository.checkAndDeleteTemporaryRoom(roomCode)
        }
    }
}
