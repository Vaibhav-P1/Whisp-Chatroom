package com.example.wishpchatroom

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.MessageStatus
import com.example.wishpchatroom.data.formatRemaining
import com.example.wishpchatroom.data.WhisperLifetimes
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.SupportedReactions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageList(
    messages: List<Message>,
    currentUid: String?,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onRetry: (messageId: String) -> Unit,
    onMessageActions: (Message) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    modifier: Modifier = Modifier,
    blockedUids: Set<String> = emptySet(),
    now: Long = 0L,
    onReveal: (Message) -> Unit = {}
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
                currentUid = currentUid,
                isBlocked = message.uid in blockedUids,
                now = now,
                onReveal = { onReveal(message) },
                onRetry = { onRetry(message.id) },
                onLongClick = { onMessageActions(message) },
                onToggleReaction = { emoji -> onToggleReaction(message.id, emoji) }
            )
        }

        item(key = "footer") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageCard(
    message: Message,
    currentUid: String?,
    onRetry: () -> Unit,
    onLongClick: () -> Unit,
    onToggleReaction: (emoji: String) -> Unit,
    isBlocked: Boolean = false,
    now: Long = 0L,
    onReveal: () -> Unit = {}
) {
    // A blocked user's message stays collapsed until tapped; this is per message and not remembered.
    var revealed by remember { mutableStateOf(false) }
    if (isBlocked && !revealed && message.uid != currentUid) {
        Text(
            text = stringResource(R.string.blocked_message),
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { revealed = true }
        )
        return
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isCurrentUser = message.uid == currentUid
    val isFailed = message.status == MessageStatus.FAILED
    val contentColor = if (isCurrentUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = { if (isFailed) onRetry() },
                    // Messages that are still sending, failed, or deleted have no actions.
                    onLongClick = {
                        if (message.status == MessageStatus.SENT && !message.deleted) onLongClick()
                    }
                ),
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

                message.replyTo?.let { reply ->
                    ReplyQuote(reply = reply, contentColor = contentColor)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                val sealed = message.isSealedFor(currentUid)
                if (sealed && !message.deleted) {
                    Text(
                        text = stringResource(R.string.whisper_sealed, whisperLifetimeLabel(message.ttlSeconds)),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = contentColor,
                        modifier = Modifier.clickable(onClick = onReveal)
                    )
                } else if (message.deleted) {
                    Text(
                        text = stringResource(R.string.message_deleted),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        text = message.messageText,
                        fontSize = 14.sp,
                        color = contentColor
                    )
                }

                if (message.isWhisper && !message.deleted) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            message.firstViewedAt != null && message.hideAt != null ->
                                stringResource(R.string.whisper_countdown, formatRemaining(message.hideAt!! - now))
                            else -> stringResource(R.string.whisper_waiting, whisperLifetimeLabel(message.ttlSeconds))
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor.copy(alpha = 0.85f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                val statusText = when (message.status) {
                    MessageStatus.SENDING -> stringResource(R.string.message_sending)
                    MessageStatus.FAILED -> stringResource(R.string.message_failed_retry)
                    MessageStatus.SENT -> timeFormat.format(Date(message.timestamp))
                }
                Text(
                    text = if (message.edited && !message.deleted) {
                        stringResource(R.string.message_edited, statusText)
                    } else {
                        statusText
                    },
                    fontSize = 10.sp,
                    color = if (isFailed) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        contentColor.copy(alpha = 0.7f)
                    }
                )

                if (message.reactions.isNotEmpty() && !message.deleted) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        message.reactions.forEach { (emoji, uids) ->
                            ReactionChip(
                                emoji = emoji,
                                count = uids.size,
                                selectedByMe = currentUid != null && currentUid in uids,
                                onClick = { onToggleReaction(emoji) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The quoted message shown at the top of a reply bubble. */
@Composable
private fun ReplyQuote(reply: ReplyRef, contentColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(contentColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(contentColor.copy(alpha = 0.6f), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = reply.username,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = reply.text,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = contentColor.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    selectedByMe: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .background(
                if (selectedByMe) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
                shape
            )
            .border(
                1.dp,
                if (selectedByMe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** What happens when a message is long-pressed: react, reply, copy, edit or delete. */
@Composable
fun MessageActionsDialog(
    message: Message,
    isMine: Boolean,
    isRoomOwner: Boolean,
    isRoomOpen: Boolean,
    onReact: (emoji: String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onSaveNote: () -> Unit = {}
) {
    // Whispers cannot be quoted, edited, copied or saved from the app (a deterrent, not a guarantee).
    val isWhisper = message.isWhisper
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column {
                if (isRoomOpen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SupportedReactions.forEach { emoji ->
                            TextButton(onClick = { onReact(emoji) }) {
                                Text(text = emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
                if (isRoomOpen && !isWhisper) ActionButton(stringResource(R.string.reply), onReply)
                if (!isWhisper) {
                    ActionButton(stringResource(R.string.copy_text), onCopy)
                    ActionButton(stringResource(R.string.mission_save_note), onSaveNote)
                }
                if (isMine && isRoomOpen && !isWhisper) ActionButton(stringResource(R.string.edit_message), onEdit)
                if (isMine || isRoomOwner) {
                    ActionButton(stringResource(R.string.delete_message), onDelete, isDestructive = true)
                }
                if (!isMine) {
                    ActionButton(stringResource(R.string.report), onReport)
                    ActionButton(stringResource(R.string.block_user), onBlock, isDestructive = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_message)) },
        text = { Text(stringResource(R.string.delete_message_text)) },
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

/** The strip above the input while replying to or editing a message. */
@Composable
fun ComposerBanner(
    title: String,
    text: String,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInput(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    // Seconds a whisper lasts after it is opened, or null for a normal message.
    whisperTtl: Long? = null,
    onWhisperTtlChange: ((Long?) -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (onWhisperTtlChange != null && enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = whisperTtl == null,
                    onClick = { onWhisperTtlChange(null) },
                    label = { Text(stringResource(R.string.whisper_off), fontSize = 12.sp) }
                )
                WhisperLifetimes.forEach { (seconds, label) ->
                    FilterChip(
                        selected = whisperTtl == seconds,
                        onClick = { onWhisperTtlChange(seconds) },
                        label = { Text("🔒 " + label, fontSize = 12.sp) }
                    )
                }
            }
            if (whisperTtl != null) {
                Text(
                    text = stringResource(R.string.whisper_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                placeholder = {
                    Text(
                        stringResource(if (enabled) R.string.message_hint else R.string.room_closed_hint),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

private fun whisperLifetimeLabel(ttlSeconds: Long?): String =
    WhisperLifetimes.firstOrNull { it.first == ttlSeconds }?.second ?: "a short while"
