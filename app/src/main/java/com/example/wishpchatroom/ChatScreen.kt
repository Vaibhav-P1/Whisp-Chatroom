package com.example.wishpchatroom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.MessageStatus
import com.example.wishpchatroom.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    roomCode: String,
    onNavigateBack: () -> Unit
) {
    val chatViewModel: ChatViewModel = hiltViewModel()

    var messageText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(roomCode) {
        chatViewModel.loadRoom(roomCode)
        chatViewModel.loadMessages(roomCode)
    }

    // System back leaves the room the same way the back arrow does.
    BackHandler { chatViewModel.leaveRoom(roomCode, onNavigateBack) }

    if (showDeleteDialog) {
        DeleteRoomDialog(
            onConfirm = {
                showDeleteDialog = false
                chatViewModel.deleteRoom(roomCode, onNavigateBack)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ChatTopBar(
            roomCode = roomCode,
            isRoomOwner = uiState.isOwner,
            onBack = { chatViewModel.leaveRoom(roomCode, onNavigateBack) },
            onClose = { chatViewModel.closeRoom(roomCode, onNavigateBack) },
            onDelete = { showDeleteDialog = true }
        )

        MessageList(
            messages = uiState.messages,
            currentUid = chatViewModel.currentUid,
            isLoadingOlder = uiState.isLoadingOlder,
            onLoadOlder = { chatViewModel.loadOlderMessages(roomCode) },
            onRetry = { messageId -> chatViewModel.retryMessage(roomCode, messageId) },
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

        MessageInput(
            text = messageText,
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

@Composable
private fun DeleteRoomDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_room)) },
        text = { Text(stringResource(R.string.delete_room_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    roomCode: String,
    isRoomOwner: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
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
            IconButton(
                onClick = {
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(roomCodeLabel, roomCode))
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_room_code),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            IconButton(
                onClick = {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareMessage)
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                }
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.share_room_code),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            if (isRoomOwner) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.close), fontSize = 12.sp)
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_room),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun MessageList(
    messages: List<Message>,
    currentUid: String?,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onRetry: (messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Items are: header, the messages, footer. Scroll to the end only when a new message arrives,
    // not when older history is added above. Scrolling to the footer always reveals the end of the
    // list, even when the newest message is taller than the screen.
    val newestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null) {
            listState.animateScrollToItem(messages.size + 1)
        }
    }

    // Reaching the top of the list reveals older history.
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(isAtTop, messages.size) {
        if (isAtTop && messages.isNotEmpty()) onLoadOlder()
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            if (isLoadingOlder) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        items(messages, key = { it.id }) { message ->
            MessageCard(
                message = message,
                isCurrentUser = message.uid == currentUid,
                onRetry = { onRetry(message.id) }
            )
        }

        item(key = "footer") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        stringResource(R.string.message_hint),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

@Composable
fun MessageCard(
    message: Message,
    isCurrentUser: Boolean,
    onRetry: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isFailed = message.status == MessageStatus.FAILED

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(max = 280.dp)
                .then(if (isFailed) Modifier.clickable(onClick = onRetry) else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrentUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isCurrentUser) 16.dp else 4.dp,
                bottomEnd = if (isCurrentUser) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (!isCurrentUser) {
                    Text(
                        text = message.username,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.messageText,
                    fontSize = 14.sp,
                    color = if (isCurrentUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (message.status) {
                        MessageStatus.SENDING -> stringResource(R.string.message_sending)
                        MessageStatus.FAILED -> stringResource(R.string.message_failed_retry)
                        MessageStatus.SENT -> timeFormat.format(Date(message.timestamp))
                    },
                    fontSize = 10.sp,
                    color = when {
                        isFailed -> MaterialTheme.colorScheme.errorContainer
                        isCurrentUser -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    }
                )
            }
        }
    }
}
