package com.example.wishpchatroom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.viewmodel.ChatViewModel
import com.example.wishpchatroom.viewmodel.RoomStatus

@Composable
fun ChatScreen(
    roomCode: String,
    onNavigateBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    var messageText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    // The message whose actions dialog is open, and the one waiting for delete confirmation.
    var actionsMessage by remember { mutableStateOf<Message?>(null) }
    var messageToDelete by remember { mutableStateOf<Message?>(null) }

    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val isRoomOpen = uiState.roomStatus == RoomStatus.OPEN
    val currentUid = chatViewModel.currentUid

    LaunchedEffect(roomCode) {
        chatViewModel.loadRoom(roomCode)
        chatViewModel.loadMessages(roomCode)
    }

    // Starting an edit puts the message text into the input box.
    LaunchedEffect(uiState.editing?.id) {
        uiState.editing?.let { messageText = it.messageText }
    }

    // System back leaves the room the same way the back arrow does; while searching it closes the search.
    BackHandler { chatViewModel.leaveRoom(roomCode, onNavigateBack) }
    BackHandler(enabled = uiState.isSearching) { chatViewModel.closeSearch() }

    if (showDeleteDialog) {
        DeleteRoomDialog(
            onConfirm = {
                showDeleteDialog = false
                chatViewModel.deleteRoom(roomCode, onNavigateBack)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showMembers) {
        MembersDialog(
            members = uiState.members,
            isOwner = uiState.isOwner,
            onLeave = {
                showMembers = false
                showLeaveDialog = true
            },
            onDismiss = { showMembers = false }
        )
    }

    if (showLeaveDialog) {
        LeaveRoomDialog(
            onConfirm = {
                showLeaveDialog = false
                chatViewModel.leaveRoomPermanently(roomCode, onNavigateBack)
            },
            onDismiss = { showLeaveDialog = false }
        )
    }

    if (uiState.roomStatus == RoomStatus.DELETED) {
        RoomDeletedDialog(
            onConfirm = { chatViewModel.acknowledgeRoomDeleted(roomCode, onNavigateBack) }
        )
    }

    actionsMessage?.let { message ->
        MessageActionsDialog(
            message = message,
            isMine = message.uid == currentUid,
            isRoomOwner = uiState.isOwner,
            isRoomOpen = isRoomOpen,
            onReact = { emoji ->
                actionsMessage = null
                chatViewModel.toggleReaction(roomCode, message.id, emoji)
            },
            onReply = {
                actionsMessage = null
                chatViewModel.startReply(message)
            },
            onCopy = {
                actionsMessage = null
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(message.username, message.messageText))
            },
            onEdit = {
                actionsMessage = null
                chatViewModel.startEdit(message)
            },
            onDelete = {
                actionsMessage = null
                messageToDelete = message
            },
            onDismiss = { actionsMessage = null }
        )
    }

    messageToDelete?.let { message ->
        DeleteMessageDialog(
            onConfirm = {
                messageToDelete = null
                chatViewModel.deleteMessage(roomCode, message.id)
            },
            onDismiss = { messageToDelete = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        if (uiState.isSearching) {
            ChatSearchBar(
                query = uiState.searchQuery,
                onQueryChange = chatViewModel::updateSearchQuery,
                onClose = chatViewModel::closeSearch
            )
            SearchResults(
                query = uiState.searchQuery,
                results = uiState.searchResults,
                currentUid = currentUid,
                modifier = Modifier.weight(1f)
            )
        } else {
            ChatTopBar(
                roomCode = roomCode,
                isRoomOwner = uiState.isOwner,
                memberCount = uiState.members.size,
                onBack = { chatViewModel.leaveRoom(roomCode, onNavigateBack) },
                onSearch = { chatViewModel.startSearch(roomCode) },
                onMembers = { showMembers = true },
                onClose = { chatViewModel.closeRoom(roomCode, onNavigateBack) },
                onDelete = { showDeleteDialog = true }
            )

            if (uiState.roomStatus == RoomStatus.CLOSED) {
                Text(
                    text = stringResource(R.string.room_closed_banner),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            MessageList(
                messages = uiState.messages,
                currentUid = currentUid,
                isLoadingOlder = uiState.isLoadingOlder,
                onLoadOlder = { chatViewModel.loadOlderMessages(roomCode) },
                onRetry = { messageId -> chatViewModel.retryMessage(roomCode, messageId) },
                onMessageActions = { actionsMessage = it },
                onToggleReaction = { messageId, emoji -> chatViewModel.toggleReaction(roomCode, messageId, emoji) },
                modifier = Modifier.weight(1f)
            )

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            uiState.replyingTo?.let { quoted ->
                ComposerBanner(
                    title = stringResource(R.string.replying_to, quoted.username),
                    text = quoted.messageText,
                    onCancel = { chatViewModel.cancelComposerMode() }
                )
            }

            uiState.editing?.let { editing ->
                ComposerBanner(
                    title = stringResource(R.string.editing_message),
                    text = editing.messageText,
                    onCancel = {
                        chatViewModel.cancelComposerMode()
                        messageText = ""
                    }
                )
            }

            MessageInput(
                text = messageText,
                enabled = isRoomOpen,
                onTextChange = {
                    messageText = it
                    chatViewModel.clearError()
                },
                onSend = {
                    if (messageText.isNotBlank() && chatViewModel.sendMessage(roomCode, messageText)) {
                        messageText = ""
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    roomCode: String,
    isRoomOwner: Boolean,
    memberCount: Int,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onMembers: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    // Resolved up front because the click handlers below are not composable.
    val roomCodeLabel = stringResource(R.string.room_code)
    val shareTitle = stringResource(R.string.share_room_code)
    val shareMessage = stringResource(R.string.share_room_message, roomCode)

    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.room_title, roomCode),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                if (isRoomOwner) {
                    Text(
                        text = stringResource(R.string.owner_label),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_messages),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Shows who is in the room (and who is online); the count is part of the description
            // so screen readers announce it too.
            IconButton(onClick = onMembers) {
                Icon(
                    Icons.Default.People,
                    contentDescription = stringResource(R.string.members_title, memberCount),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_room_code)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboardManager.setPrimaryClip(ClipData.newPlainText(roomCodeLabel, roomCode))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_room_code)) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, shareMessage)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    }
                )
                if (isRoomOwner) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.close_room)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onClose()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.delete_room), color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    )
}
