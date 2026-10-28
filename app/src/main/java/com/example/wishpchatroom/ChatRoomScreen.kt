package com.example.wishpchatroom

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserPrefs
import com.example.wishpchatroom.ui.components.ErrorCard
import com.example.wishpchatroom.viewmodel.AuthViewModel
import com.example.wishpchatroom.data.MAX_MISSION_GOAL
import com.example.wishpchatroom.data.MAX_MISSION_TITLE
import com.example.wishpchatroom.data.MissionDuration
import com.example.wishpchatroom.data.MissionSpec
import com.example.wishpchatroom.viewmodel.RoomViewModel

@Composable
fun ChatRoomScreen(
    authViewModel: AuthViewModel,
    onJoinRoom: (String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onOpenInbox: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    // A room code from an invite link: opens the form in join mode with the code filled in.
    initialJoinCode: String? = null
) {
    val roomViewModel: RoomViewModel = hiltViewModel()
    val context = LocalContext.current
    val sharedPref = context.getSharedPreferences(UserPrefs.FILE_NAME, Context.MODE_PRIVATE)

    var roomCode by remember { mutableStateOf(initialJoinCode.orEmpty().uppercase()) }
    var username by remember { mutableStateOf(sharedPref.getString(UserPrefs.KEY_DEFAULT_USERNAME, "") ?: "") }
    var isCreateMode by remember { mutableStateOf(initialJoinCode == null) }
    var isTemporary by remember { mutableStateOf(false) }
    var isMission by remember { mutableStateOf(false) }
    var missionTitle by remember { mutableStateOf("") }
    var missionGoal by remember { mutableStateOf("") }
    var missionDuration by remember { mutableStateOf(MissionDuration.SIX_HOURS) }
    var useSavedUsername by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }

    val roomState by roomViewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    // Each created/joined room arrives exactly once, so navigation cannot fire twice.
    LaunchedEffect(Unit) {
        roomViewModel.roomEntered.collect { enteredRoomCode ->
            if (useSavedUsername && username.isNotBlank()) {
                sharedPref.edit().putString(UserPrefs.KEY_DEFAULT_USERNAME, username.trim()).apply()
            }
            onJoinRoom(enteredRoomCode)
        }
    }

    // The account is gone: leave the signed-in screens (the Auth user and local cache are already cleared).
    LaunchedEffect(Unit) {
        authViewModel.accountDeleted.collect { onNavigateToLogin() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        RoomModeSelector(
            isCreateMode = isCreateMode,
            onModeSelected = { isCreateMode = it }
        )

        Spacer(modifier = Modifier.height(24.dp))

        RoomForm(
            username = username,
            onUsernameChange = { username = it },
            useSavedUsername = useSavedUsername,
            onUseSavedUsernameChange = {
                useSavedUsername = it
                if (it && username.isBlank()) {
                    username = sharedPref.getString(UserPrefs.KEY_DEFAULT_USERNAME, "") ?: ""
                }
            },
            isCreateMode = isCreateMode,
            roomCode = roomCode,
            onRoomCodeChange = { roomCode = it.uppercase() },
            isTemporary = isTemporary,
            onTemporaryChange = { isTemporary = it },
            isMission = isMission,
            onMissionChange = { isMission = it },
            missionTitle = missionTitle,
            onMissionTitleChange = { missionTitle = it },
            missionGoal = missionGoal,
            onMissionGoalChange = { missionGoal = it },
            missionDuration = missionDuration,
            onMissionDurationChange = { missionDuration = it },
            errorMessage = roomState.errorMessage,
            isLoading = roomState.isLoading,
            onSubmit = {
                if (isCreateMode) {
                    val spec = if (isMission) MissionSpec(missionTitle, missionGoal, missionDuration.seconds) else null
                    roomViewModel.createRoom(username, isTemporary, spec)
                } else {
                    roomViewModel.joinRoom(roomCode, username)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenInbox, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.open_messages))
            }
            OutlinedButton(onClick = onOpenFriends, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.open_friends))
            }
        }
        OutlinedButton(onClick = onOpenNotes, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_notes))
        }

        Spacer(modifier = Modifier.height(24.dp))

        AccountCard(
            user = authState.currentUser,
            onLogout = {
                authViewModel.logout()
                onNavigateToLogin()
            },
            onDeleteAccount = { showDeleteAccount = true }
        )

        if (showDeleteAccount) {
            DeleteAccountDialog(
                isDeleting = authState.isDeleting,
                errorMessage = authState.deleteError,
                onConfirm = authViewModel::deleteAccount,
                onDismiss = {
                    showDeleteAccount = false
                    authViewModel.clearDeleteError()
                }
            )
        }
    }
}

@Composable
private fun RoomModeSelector(
    isCreateMode: Boolean,
    onModeSelected: (isCreateMode: Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.select_action),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton(
                    text = stringResource(R.string.create_room),
                    selected = isCreateMode,
                    onClick = { onModeSelected(true) },
                    modifier = Modifier.weight(1f)
                )
                ModeButton(
                    text = stringResource(R.string.join_room),
                    selected = !isCreateMode,
                    onClick = { onModeSelected(false) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomForm(
    username: String,
    onUsernameChange: (String) -> Unit,
    useSavedUsername: Boolean,
    onUseSavedUsernameChange: (Boolean) -> Unit,
    isCreateMode: Boolean,
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    isTemporary: Boolean,
    onTemporaryChange: (Boolean) -> Unit,
    isMission: Boolean,
    onMissionChange: (Boolean) -> Unit,
    missionTitle: String,
    onMissionTitleChange: (String) -> Unit,
    missionGoal: String,
    onMissionGoalChange: (String) -> Unit,
    missionDuration: MissionDuration,
    onMissionDurationChange: (MissionDuration) -> Unit,
    errorMessage: String?,
    isLoading: Boolean,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.username), color = MaterialTheme.colorScheme.onSurface) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            LabeledCheckbox(
                checked = useSavedUsername,
                onCheckedChange = onUseSavedUsernameChange,
                label = stringResource(R.string.save_default_username),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isCreateMode) {
                if (!isMission) {
                    LabeledCheckbox(
                        checked = isTemporary,
                        onCheckedChange = onTemporaryChange,
                        label = stringResource(R.string.temporary_room)
                    )
                }
                LabeledCheckbox(
                    checked = isMission,
                    onCheckedChange = onMissionChange,
                    label = stringResource(R.string.mission_room)
                )
                if (isMission) {
                    OutlinedTextField(
                        value = missionTitle,
                        onValueChange = { if (it.length <= MAX_MISSION_TITLE) onMissionTitleChange(it) },
                        label = { Text(stringResource(R.string.mission_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = missionGoal,
                        onValueChange = { if (it.length <= MAX_MISSION_GOAL) onMissionGoalChange(it) },
                        label = { Text(stringResource(R.string.mission_goal)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.mission_duration), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MissionDuration.values().forEach { option ->
                            FilterChip(
                                selected = option == missionDuration,
                                onClick = { onMissionDurationChange(option) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.mission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = onRoomCodeChange,
                    label = { Text(stringResource(R.string.room_code), color = MaterialTheme.colorScheme.onSurface) },
                    placeholder = { Text(stringResource(R.string.room_code_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (errorMessage != null) {
                ErrorCard(message = errorMessage)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && username.isNotBlank() && (isCreateMode || roomCode.isNotBlank())
            ) {
                Text(
                    text = if (isCreateMode) stringResource(R.string.create_and_join_room) else stringResource(R.string.join_room),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LabeledCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun AccountCard(
    user: User?,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            user?.let {
                Text(
                    text = stringResource(R.string.logged_in_as),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "${it.firstName} ${it.lastName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.logout),
                    fontSize = 16.sp
                )
            }

            TextButton(onClick = onDeleteAccount) {
                Text(
                    text = stringResource(R.string.delete_account),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
