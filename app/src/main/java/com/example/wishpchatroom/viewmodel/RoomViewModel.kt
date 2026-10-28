package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.MissionSpec
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.RoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val roomRepository: RoomRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    // Emits the room code once a room was created or joined. A Channel is used instead of state so
    // the navigation is delivered once and is not replayed after rotation or coming back.
    private val _roomEntered = Channel<String>(Channel.BUFFERED)
    val roomEntered: Flow<String> = _roomEntered.receiveAsFlow()

    fun createRoom(username: String, isTemporary: Boolean, mission: MissionSpec? = null) {
        // A mission ends on its own, so it is never also a creator-leaves temporary room.
        submit { roomRepository.createRoom(username, isTemporary && mission == null, mission) }
    }

    fun joinRoom(roomCode: String, username: String) {
        val code = roomCode.trim().uppercase()
        submit {
            when (val result = roomRepository.joinRoom(code, username)) {
                is Resource.Success -> Resource.Success(code)
                is Resource.Error -> result
            }
        }
    }

    private fun submit(action: suspend () -> Resource<String>) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { RoomUiState(isLoading = true) }
            when (val result = action()) {
                is Resource.Success -> {
                    _uiState.update { RoomUiState() }
                    _roomEntered.send(result.data)
                }
                is Resource.Error -> _uiState.update { RoomUiState(errorMessage = result.message) }
            }
        }
    }
}
