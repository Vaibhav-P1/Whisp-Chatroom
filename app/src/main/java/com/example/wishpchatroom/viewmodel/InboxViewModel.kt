package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.ContactsRepository
import com.example.wishpchatroom.data.InboxItem
import com.example.wishpchatroom.data.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val archived: List<InboxItem> = emptyList(),
    val showArchived: Boolean = false,
    val errorMessage: String? = null
) {
    /** Conversations with something new that the user has not muted. */
    val unreadCount: Int get() = items.count { it.unread && !it.muted }
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val contacts: ContactsRepository
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    // Listeners exist only while the inbox screen collects this.
    val uiState: StateFlow<InboxUiState> = combine(contacts.observeInbox(), showArchived, error) { all, archived, message ->
        InboxUiState(
            items = all.filter { !it.archived },
            archived = all.filter { it.archived },
            showArchived = archived,
            errorMessage = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InboxUiState())

    fun toggleArchivedSection() {
        showArchived.update { !it }
    }

    fun setPinned(item: InboxItem, pinned: Boolean) = save { contacts.setPinned(item.roomCode, pinned) }

    fun setMuted(item: InboxItem, muted: Boolean) = save { contacts.setMuted(item.roomCode, muted) }

    fun setArchived(item: InboxItem, archived: Boolean) = save { contacts.setArchived(item.roomCode, archived) }

    fun clearError() {
        error.value = null
    }

    private fun save(action: suspend () -> Resource<Unit>) {
        viewModelScope.launch {
            val result = action()
            if (result is Resource.Error) error.value = result.message
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
