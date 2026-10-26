package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Result
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    // Make these nullable by adding ?
    private val _authResult = MutableLiveData<Result<Boolean>?>()
    val authResult: LiveData<Result<Boolean>?> get() = _authResult

    private val _currentUser = MutableLiveData<User?>()
    val currentUser: LiveData<User?> get() = _currentUser

    init {
        checkCurrentUser()
    }

    // Firebase restores the session synchronously, so this is safe to read when choosing
    // the start destination (the profile in currentUser still loads asynchronously).
    fun isLoggedIn(): Boolean = userRepository.isLoggedIn()

    /** Clears a handled result so it is not replayed after rotation or a later navigation. */
    fun consumeAuthResult() {
        _authResult.value = null
    }

    private fun checkCurrentUser() {
        viewModelScope.launch {
            val user = userRepository.getCurrentUser()
            _currentUser.value = user
        }
    }

    fun signUp(email: String, password: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            _authResult.value = userRepository.signUp(email, password, firstName, lastName)
            if (_authResult.value is Result.Success) {
                checkCurrentUser()
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authResult.value = userRepository.login(email, password)
            if (_authResult.value is Result.Success) {
                checkCurrentUser()
            }
        }
    }

    fun logout() {
        userRepository.logout()
        _currentUser.value = null
        _authResult.value = null
    }
}