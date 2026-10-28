package com.example.wishpchatroom

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.MAX_ANNOUNCEMENT
import com.example.wishpchatroom.data.MAX_OUTCOME
import com.example.wishpchatroom.data.MAX_TASK_TEXT
import com.example.wishpchatroom.data.MISSION_URGENT_MS
import com.example.wishpchatroom.data.Mission
import com.example.wishpchatroom.data.MissionPhase
import com.example.wishpchatroom.data.formatRemaining
import com.example.wishpchatroom.viewmodel.MissionViewModel

/** The strip under the top bar of a mission room: the title and a live countdown. */
@Composable
fun MissionBanner(
    mission: Mission,
    expiresAt: Long?,
    now: Long,
    phase: MissionPhase,
    onClick: () -> Unit
) {
    val remaining = expiresAt?.minus(now)
    val urgent = phase == MissionPhase.ACTIVE && remaining != null && remaining <= MISSION_URGENT_MS
    val ended = phase != MissionPhase.ACTIVE
    val container = when {
        ended -> MaterialTheme.colorScheme.errorContainer
        urgent -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        ended -> MaterialTheme.colorScheme.onErrorContainer
        urgent -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(container)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🎯 " + mission.title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = content,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (ended) stringResource(R.string.mission_ended_short)
                else stringResource(R.string.mission_remaining, formatRemaining(remaining ?: 0)),
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = content
            )
        }
        if (mission.announcement.isNotBlank() && !ended) {
            Text(text = mission.announcement, fontSize = 12.sp, color = content, modifier = Modifier.padding(top = 2.dp))
        }
        if (ended) {
            Text(
                text = stringResource(R.string.mission_ended_banner),
                fontSize = 12.sp,
                color = content,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** The mission details: goal, announcement, checklist and the final outcome. */
@Composable
fun MissionDialog(
    roomCode: String,
    roomTitleForNotes: String,
    myName: String,
    mission: Mission,
    phase: MissionPhase,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    viewModel: MissionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = phase == MissionPhase.ACTIVE
    var announcement by remember { mutableStateOf(mission.announcement) }
    var outcome by remember { mutableStateOf(mission.outcome) }
    var newTask by remember { mutableStateOf("") }

    // The checklist listener runs only while this dialog is open.
    DisposableEffect(roomCode) {
        viewModel.startWatching(roomCode)
        onDispose {
            viewModel.stopWatching()
            viewModel.clearMessages()
        }
    }

    val inviteLink = "wishp://join/$roomCode"
    val inviteText = stringResource(R.string.mission_invite_message, mission.title, inviteLink, roomCode)
    val shareTitle = stringResource(R.string.mission_share_link)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mission.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                state.infoMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }

                if (mission.goal.isNotBlank()) {
                    Section(stringResource(R.string.mission_goal_label))
                    Text(mission.goal, fontSize = 14.sp)
                }

                Section(stringResource(R.string.mission_announcement))
                if (isOwner && active) {
                    OutlinedTextField(
                        value = announcement,
                        onValueChange = { if (it.length <= MAX_ANNOUNCEMENT) announcement = it },
                        label = { Text(stringResource(R.string.mission_announcement_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    OutlinedButton(onClick = { viewModel.setAnnouncement(roomCode, announcement) }) {
                        Text(stringResource(R.string.mission_post))
                    }
                } else {
                    Text(mission.announcement.ifBlank { "-" }, fontSize = 14.sp)
                }

                Section(stringResource(R.string.mission_checklist))
                state.tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { viewModel.setTaskDone(roomCode, task, it) },
                            enabled = active
                        )
                        Text(task.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        if (isOwner && active) {
                            TextButton(onClick = { viewModel.deleteTask(roomCode, task) }) {
                                Text(stringResource(R.string.delete), fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (isOwner && active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTask,
                            onValueChange = { if (it.length <= MAX_TASK_TEXT) newTask = it },
                            label = { Text(stringResource(R.string.mission_add_task_hint)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(
                            onClick = {
                                viewModel.addTask(roomCode, newTask)
                                newTask = ""
                            },
                            enabled = newTask.isNotBlank()
                        ) { Text(stringResource(R.string.mission_add)) }
                    }
                }
                if (state.tasks.isNotEmpty()) {
                    TextButton(onClick = {
                        val summary = state.tasks.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }
                        viewModel.saveNote(roomTitleForNotes, myName, summary, "tasks")
                    }) { Text(stringResource(R.string.mission_save_tasks)) }
                }

                Section(stringResource(R.string.mission_outcome))
                if (isOwner) {
                    OutlinedTextField(
                        value = outcome,
                        onValueChange = { if (it.length <= MAX_OUTCOME) outcome = it },
                        label = { Text(stringResource(R.string.mission_outcome_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                    OutlinedButton(onClick = { viewModel.setOutcome(roomCode, outcome) }) {
                        Text(stringResource(R.string.mission_save_outcome))
                    }
                } else {
                    Text(mission.outcome.ifBlank { "-" }, fontSize = 14.sp)
                }
                if (mission.outcome.isNotBlank()) {
                    TextButton(onClick = { viewModel.saveNote(roomTitleForNotes, myName, mission.outcome, "outcome") }) {
                        Text(stringResource(R.string.mission_save_note))
                    }
                }

                if (active) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, inviteText)
                            }
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(shareTitle) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}
