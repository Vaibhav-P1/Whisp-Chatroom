package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.MIN_PASSWORD_LENGTH
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserRepository
import com.example.wishpchatroom.data.isValidEmail
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
    val currentUser: User? = null,
    /** True once a password reset email was requested, until the dialog is dismissed. */
    val resetEmailSent: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteError: String? = null
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

    // Emitted once the account is gone, so the UI can leave the signed-in screens.
    private val _accountDeleted = Channel<Unit>(Channel.BUFFERED)
    val accountDeleted: Flow<Unit> = _accountDeleted.receiveAsFlow()

    init {
        viewModelScope.launch { refreshCurrentUser() }
    }

    // Firebase restores the session synchronously, so this is safe to read when choosing
    // the start destination (the profile in currentUser still loads asynchronously).
    fun isLoggedIn(): Boolean = userRepository.isLoggedIn()

    fun signUp(email: String, password: String, firstName: String, lastName: String) {
        validateCredentials(email, password, checkLength = true)?.let { return showError(it) }
        authenticate { userRepository.signUp(email, password, firstName, lastName) }
    }

    fun login(email: String, password: String) {
        validateCredentials(email, password, checkLength = false)?.let { return showError(it) }
        authenticate { userRepository.login(email, password) }
    }

    fun sendPasswordReset(email: String) {
        if (_uiState.value.isLoading) return
        if (!isValidEmail(email)) return showError("Enter a valid email address.")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = userRepository.sendPasswordReset(email)) {
                is Resource.Success -> _uiState.update { it.copy(isLoading = false, resetEmailSent = true) }
                is Resource.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
        }
    }

    fun dismissResetConfirmation() {
        _uiState.update { it.copy(resetEmailSent = false) }
    }

    fun deleteAccount(password: String) {
        if (_uiState.value.isDeleting) return
        if (password.isBlank()) {
            _uiState.update { it.copy(deleteError = "Enter your password to confirm.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, deleteError = null) }
            when (val result = userRepository.deleteAccount(password)) {
                is Resource.Success -> {
                    _uiState.value = AuthUiState()
                    _accountDeleted.send(Unit)
                }
                is Resource.Error -> _uiState.update { it.copy(isDeleting = false, deleteError = result.message) }
            }
        }
    }

    fun clearDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun logout() {
        userRepository.logout()
        _uiState.value = AuthUiState()
        viewModelScope.launch { userRepository.clearLocalData() }
    }

    private fun validateCredentials(email: String, password: String, checkLength: Boolean): String? = when {
        !isValidEmail(email) -> "Enter a valid email address."
        checkLength && password.length < MIN_PASSWORD_LENGTH ->
            "Password must be at least $MIN_PASSWORD_LENGTH characters."
        else -> null
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
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
