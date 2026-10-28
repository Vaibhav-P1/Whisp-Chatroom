package com.example.wishpchatroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.Friend
import com.example.wishpchatroom.data.FriendRequest
import com.example.wishpchatroom.viewmodel.ContactsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenChat: (roomCode: String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var friendToRemove by remember { mutableStateOf<Friend?>(null) }

    friendToRemove?.let { friend ->
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            title = { Text(stringResource(R.string.remove_friend_title, friend.handle)) },
            text = { Text(stringResource(R.string.remove_friend_message)) },
            confirmButton = {
                TextButton(onClick = {
                    friendToRemove = null
                    viewModel.removeFriend(friend)
                }) {
                    Text(stringResource(R.string.remove_friend), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { friendToRemove = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.friends_title), color = MaterialTheme.colorScheme.onPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "status") {
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                state.infoMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            item(key = "handle") {
                HandleCard(
                    currentHandle = state.myHandle,
                    enabled = !state.isBusy,
                    onClaim = viewModel::claimHandle,
                    onEdit = viewModel::clearMessages
                )
            }

            item(key = "add") {
                AddFriendCard(
                    enabled = !state.isBusy && state.myHandle != null,
                    onSend = viewModel::sendRequest
                )
            }

            if (state.incoming.isNotEmpty()) {
                item(key = "incoming-title") { SectionTitle(stringResource(R.string.requests_received, state.incoming.size)) }
                items(state.incoming, key = { "in-" + it.id }) { request ->
                    RequestRow(
                        request = request,
                        primaryLabel = stringResource(R.string.accept),
                        secondaryLabel = stringResource(R.string.decline),
                        enabled = !state.isBusy,
                        onPrimary = { viewModel.accept(request) },
                        onSecondary = { viewModel.decline(request) }
                    )
                }
            }

            if (state.outgoing.isNotEmpty()) {
                item(key = "outgoing-title") { SectionTitle(stringResource(R.string.requests_sent, state.outgoing.size)) }
                items(state.outgoing, key = { "out-" + it.id }) { request ->
                    RequestRow(
                        request = request,
                        primaryLabel = null,
                        secondaryLabel = stringResource(R.string.cancel),
                        enabled = !state.isBusy,
                        onPrimary = {},
                        onSecondary = { viewModel.cancel(request) },
                        showSender = false
                    )
                }
            }

            item(key = "friends-title") { SectionTitle(stringResource(R.string.friends_count, state.friends.size)) }
            if (state.friends.isEmpty()) {
                item(key = "friends-empty") {
                    Text(
                        stringResource(R.string.friends_empty),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
            items(state.friends, key = { "f-" + it.uid }) { friend ->
                FriendRow(
                    friend = friend,
                    onChat = { viewModel.dmRoomFor(friend)?.let(onOpenChat) },
                    onRemove = { friendToRemove = friend }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun HandleCard(currentHandle: String?, enabled: Boolean, onClaim: (String) -> Unit, onEdit: () -> Unit) {
    var editing by remember(currentHandle) { mutableStateOf(currentHandle == null) }
    var input by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.your_handle), fontWeight = FontWeight.Bold)
            if (!editing && currentHandle != null) {
                Text("@$currentHandle", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = {
                    onEdit()
                    editing = true
                }) { Text(stringResource(R.string.change_handle)) }
            } else {
                Text(stringResource(R.string.handle_hint), fontSize = 12.sp)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.lowercase() },
                    label = { Text(stringResource(R.string.handle_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onClaim(input) }, enabled = enabled && input.isNotBlank()) {
                    Text(stringResource(R.string.save_handle))
                }
            }
        }
    }
}

@Composable
private fun AddFriendCard(enabled: Boolean, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.add_friend), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.friend_handle_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = enabled && input.isNotBlank()
            ) { Text(stringResource(R.string.send_request)) }
        }
    }
}

@Composable
private fun RequestRow(
    request: FriendRequest,
    primaryLabel: String?,
    secondaryLabel: String,
    enabled: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    showSender: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (showSender) {
                    Text(request.fromName, fontWeight = FontWeight.Medium)
                    Text("@${request.fromHandle}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.request_pending), fontWeight = FontWeight.Medium)
                }
            }
            if (primaryLabel != null) {
                Button(onClick = onPrimary, enabled = enabled) { Text(primaryLabel) }
                Spacer(modifier = Modifier.height(0.dp).padding(start = 8.dp))
            }
            TextButton(onClick = onSecondary, enabled = enabled) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, onChat: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "@${friend.handle}",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                fontWeight = FontWeight.Medium
            )
            OutlinedButton(onClick = onChat) { Text(stringResource(R.string.message_friend)) }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.remove_friend), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}
