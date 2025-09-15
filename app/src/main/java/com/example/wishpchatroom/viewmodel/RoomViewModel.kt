package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Injection
import com.example.wishpchatroom.data.Result
import com.example.wishpchatroom.data.RoomRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class RoomViewModel : ViewModel() {
    private val roomRepository = RoomRepository(Injection.instance(), FirebaseAuth.getInstance())

    private val _roomResult = MutableLiveData<Result<String>>()
    val roomResult: LiveData<Result<String>> get() = _roomResult

    fun createRoom(username: String, isTemporary: Boolean) {
        viewModelScope.launch {
            _roomResult.value = roomRepository.createRoom(username, isTemporary)
        }
    }

    fun joinRoom(roomCode: String, username: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = roomRepository.joinRoom(roomCode, username)
            when (result) {
                is Result.Success -> {
                    _roomResult.value = Result.Success(roomCode)
                    callback(true)
                }
                is Result.Error -> {
                    _roomResult.value = result
                    callback(false)
                }
            }
        }
    }
}