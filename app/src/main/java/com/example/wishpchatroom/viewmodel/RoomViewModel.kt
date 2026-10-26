package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.RoomRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val roomRepository: RoomRepository
) : ViewModel() {

    // One-shot events (a room was created/joined, or it failed). A Channel is used instead of
    // LiveData so the result is delivered once and is not replayed after rotation or navigation.
    private val _events = Channel<Resource<String>>(Channel.BUFFERED)
    val events: Flow<Resource<String>> = _events.receiveAsFlow()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    fun createRoom(username: String, isTemporary: Boolean) {
        if (_isLoading.value == true) return
        viewModelScope.launch {
            _isLoading.value = true
            _events.send(roomRepository.createRoom(username, isTemporary))
            _isLoading.value = false
        }
    }

    fun joinRoom(roomCode: String, username: String) {
        if (_isLoading.value == true) return
        val code = roomCode.trim().uppercase()
        viewModelScope.launch {
            _isLoading.value = true
            val event = when (val result = roomRepository.joinRoom(code, username)) {
                is Resource.Success -> Resource.Success(code)
                is Resource.Error -> result
            }
            _events.send(event)
            _isLoading.value = false
        }
    }
}
