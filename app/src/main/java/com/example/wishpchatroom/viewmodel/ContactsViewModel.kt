package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ContactsRepository
import com.example.wishpchatroom.data.Friend
import com.example.wishpchatroom.data.FriendRequest
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.dmRoomId
import com.example.wishpchatroom.data.validateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactsUiState(
    val myHandle: String? = null,
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val friends: List<Friend> = emptyList(),
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

private data class Transient(
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contacts: ContactsRepository
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    // Firestore listeners run only while a screen collects this (WhileSubscribed), so nothing listens
    // while the contacts screen is hidden.
    val uiState: StateFlow<ContactsUiState> = combine(
        contacts.observeMyHandle(),
        contacts.observeIncomingRequests(),
        contacts.observeOutgoingRequests(),
        contacts.observeFriends(),
        transient
    ) { handle, incoming, outgoing, friends, t ->
        ContactsUiState(handle, incoming, outgoing, friends, t.isBusy, t.errorMessage, t.infoMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContactsUiState())

    /** The DM room code for a friend, used to open the chat. */
    fun dmRoomFor(friend: Friend): String? = contacts.currentUid?.let { dmRoomId(it, friend.uid) }

    fun claimHandle(input: String) {
        val handle = input.trim().lowercase().removePrefix("@")
        validateHandle(handle)?.let { return fail(it) }
        run("Handle saved.") { contacts.claimHandle(handle) }
    }

    fun sendRequest(input: String) {
        if (input.isBlank()) return fail("Enter a handle.")
        run("Request sent.") { contacts.sendRequest(input) }
    }

    fun accept(request: FriendRequest) {
        run("You are now friends with ${request.fromName}.") {
            when (val result = contacts.acceptRequest(request)) {
                is Resource.Success -> Resource.Success(Unit)
                is Resource.Error -> result
            }
        }
    }

    fun decline(request: FriendRequest) = run(null) { contacts.removeRequest(request) }

    fun cancel(request: FriendRequest) = run(null) { contacts.removeRequest(request) }

    fun removeFriend(friend: Friend) = run("Friend removed. The chat history is kept but locked.") {
        contacts.removeFriend(friend.uid)
    }

    fun clearMessages() {
        transient.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun fail(message: String) {
        transient.update { it.copy(errorMessage = message, infoMessage = null) }
    }

    private fun run(success: String?, action: suspend () -> Resource<Unit>) {
        if (transient.value.isBusy) return
        viewModelScope.launch {
            transient.value = Transient(isBusy = true)
            transient.value = when (val result = action()) {
                is Resource.Success -> Transient(infoMessage = success)
                is Resource.Error -> Transient(errorMessage = result.message)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
