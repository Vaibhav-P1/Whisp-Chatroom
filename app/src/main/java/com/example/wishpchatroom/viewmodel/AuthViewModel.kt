package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserRepository
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

data class AuthUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val currentUser: User? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Emitted once after a successful login or sign up, so navigation is not replayed on rotation.
    private val _authenticated = Channel<Unit>(Channel.BUFFERED)
    val authenticated: Flow<Unit> = _authenticated.receiveAsFlow()

    init {
        viewModelScope.launch { refreshCurrentUser() }
    }

    // Firebase restores the session synchronously, so this is safe to read when choosing
    // the start destination (the profile in currentUser still loads asynchronously).
    fun isLoggedIn(): Boolean = userRepository.isLoggedIn()

    fun signUp(email: String, password: String, firstName: String, lastName: String) {
        authenticate { userRepository.signUp(email, password, firstName, lastName) }
    }

    fun login(email: String, password: String) {
        authenticate { userRepository.login(email, password) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun logout() {
        userRepository.logout()
        _uiState.value = AuthUiState()
    }

    private fun authenticate(action: suspend () -> Resource<Boolean>) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = action()) {
                is Resource.Success -> {
                    refreshCurrentUser()
                    _uiState.update { it.copy(isLoading = false) }
                    _authenticated.send(Unit)
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
            }
        }
    }

    private suspend fun refreshCurrentUser() {
        val user = userRepository.getCurrentUser()
        _uiState.update { it.copy(currentUser = user) }
    }
}
