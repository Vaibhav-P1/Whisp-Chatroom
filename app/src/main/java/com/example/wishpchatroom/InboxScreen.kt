package com.example.wishpchatroom

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.InboxItem
import com.example.wishpchatroom.viewmodel.InboxViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onOpenChat: (roomCode: String) -> Unit,
    onOpenFriends: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel()
) {
    // The inbox listeners run only while this screen is started (collectAsStateWithLifecycle).
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.inbox_title), color = MaterialTheme.colorScheme.onPrimary) },
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
                IconButton(onClick = onOpenFriends) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = stringResource(R.string.friends_title),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
        )

        state.errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (state.items.isEmpty() && state.archived.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.inbox_empty),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.roomCode }) { item ->
                    InboxRow(
                        item = item,
                        onClick = { onOpenChat(item.roomCode) },
                        onPin = { viewModel.setPinned(item, !item.pinned) },
                        onMute = { viewModel.setMuted(item, !item.muted) },
                        onArchive = { viewModel.setArchived(item, !item.archived) }
                    )
                }

                if (state.archived.isNotEmpty()) {
                    item(key = "archived-toggle") {
                        TextButton(onClick = viewModel::toggleArchivedSection) {
                            Text(stringResource(R.string.archived_count, state.archived.size))
                        }
                    }
                    if (state.showArchived) {
                        items(state.archived, key = { "a-" + it.roomCode }) { item ->
                            InboxRow(
                                item = item,
                                onClick = { onOpenChat(item.roomCode) },
                                onPin = { viewModel.setPinned(item, !item.pinned) },
                                onMute = { viewModel.setMuted(item, !item.muted) },
                                onArchive = { viewModel.setArchived(item, !item.archived) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(
    item: InboxItem,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val showUnread = item.unread && !item.muted

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.friendName,
                            fontWeight = if (showUnread) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.pinned) Text("  📌", fontSize = 12.sp)
                        if (item.muted) Text("  🔕", fontSize = 12.sp)
                    }
                    Text(
                        text = when {
                            item.locked -> stringResource(R.string.inbox_locked)
                            item.lastText != null -> item.lastText
                            else -> stringResource(R.string.inbox_no_messages)
                        },
                        fontSize = 13.sp,
                        fontWeight = if (showUnread) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    item.lastAt?.let {
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (showUnread) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (item.pinned) R.string.unpin else R.string.pin)) },
                onClick = { menuOpen = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.muted) R.string.unmute else R.string.mute)) },
                onClick = { menuOpen = false; onMute() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.archived) R.string.unarchive else R.string.archive)) },
                onClick = { menuOpen = false; onArchive() }
            )
        }
    }
}
