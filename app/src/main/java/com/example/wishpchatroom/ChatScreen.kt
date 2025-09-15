package com.example.wishpchatroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.viewmodel.AuthViewModel
import com.example.wishpchatroom.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.widthIn

@Composable
fun ChatScreen(
    roomCode: String,
    onNavigateBack: () -> Unit
) {
    val chatViewModel: ChatViewModel = viewModel()
    val authViewModel: AuthViewModel = viewModel()

    var messageText by remember { mutableStateOf("") }
    var isRoomOwner by remember { mutableStateOf(false) }

    val messages by chatViewModel.messages.observeAsState(emptyList())
    val currentUser by authViewModel.currentUser.observeAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(roomCode) {
        chatViewModel.loadMessages(roomCode)
        chatViewModel.checkRoomOwnership(roomCode) { isOwner ->
            isRoomOwner = isOwner
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        TopAppBar(
            title = {
                Column {
                    Text("Room: $roomCode", fontWeight = FontWeight.Bold)
                    if (isRoomOwner) {
                        Text("(Owner)", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (isRoomOwner) {
                    OutlinedButton(
                        onClick = {
                            chatViewModel.closeRoom(roomCode)
                            onNavigateBack()
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Close Room", fontSize = 12.sp)
                    }
                }
            }
        )

        // Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageCard(
                    message = message,
                    isCurrentUser = message.username == currentUser?.firstName
                )
            }
        }

        // Message Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (messageText.isNotBlank() && currentUser != null) {
                        chatViewModel.sendMessage(roomCode, messageText, currentUser!!.firstName)
                        messageText = ""
                    }
                },
                enabled = messageText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun MessageCard(
    message: Message,
    isCurrentUser: Boolean
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(max = 280.dp),
            backgroundColor = if (isCurrentUser)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surface,
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (!isCurrentUser) {
                    Text(
                        text = message.username,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.messageText,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}