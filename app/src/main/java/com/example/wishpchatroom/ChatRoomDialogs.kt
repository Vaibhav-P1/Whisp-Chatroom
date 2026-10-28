package com.example.wishpchatroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wishpchatroom.data.Ban
import com.example.wishpchatroom.viewmodel.Member

private val OnlineColor = Color(0xFF2E7D32)

@Composable
fun DeleteRoomDialog(
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

@Composable
fun LeaveRoomDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_room)) },
        text = { Text(stringResource(R.string.leave_room_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.leave_room), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Shown when the owner deletes the room while this user is in it. Cannot be dismissed. */
@Composable
fun RoomDeletedDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.room_deleted_title)) },
        text = { Text(stringResource(R.string.room_deleted_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@Composable
fun MembersDialog(
    members: List<Member>,
    isOwner: Boolean,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    bans: List<Ban> = emptyList(),
    onRemove: (Member) -> Unit = {},
    onUnban: (Ban) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.members_title, members.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                members.forEach { member ->
                    MemberRow(member, onRemove = if (isOwner && !member.isYou) ({ onRemove(member) }) else null)
                }
                if (bans.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.banned_members, bans.size),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    bans.forEach { ban ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ban.username,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            TextButton(onClick = { onUnban(ban) }) { Text(stringResource(R.string.unban)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        // The owner cannot leave: they close or delete the room instead.
        dismissButton = if (isOwner) {
            null
        } else {
            {
                TextButton(onClick = onLeave) {
                    Text(stringResource(R.string.leave_room), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

@Composable
private fun MemberRow(member: Member, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (member.isOnline) OnlineColor else MaterialTheme.colorScheme.outline)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (member.isYou) {
                    stringResource(R.string.member_you, member.username)
                } else {
                    member.username
                },
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(if (member.isOnline) R.string.member_online else R.string.member_offline),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (member.isOwner) {
            Text(
                text = stringResource(R.string.member_owner),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.remove_member), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}
