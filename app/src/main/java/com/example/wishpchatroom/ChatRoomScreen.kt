package com.example.wishpchatroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wishpchatroom.data.Result
import com.example.wishpchatroom.viewmodel.AuthViewModel
import com.example.wishpchatroom.viewmodel.RoomViewModel

@Composable
fun ChatRoomScreen(
    onJoinRoom: (String) -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val roomViewModel: RoomViewModel = viewModel()
    val authViewModel: AuthViewModel = viewModel()

    var roomCode by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var isCreateMode by remember { mutableStateOf(true) }
    var isTemporary by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val roomResult by roomViewModel.roomResult.observeAsState()
    val currentUser by authViewModel.currentUser.observeAsState()

    LaunchedEffect(roomResult) {
        when (roomResult) {
            is com.example.wishpchatroom.data.Result.Success -> {
                val generatedRoomCode = (roomResult as Result.Success<String>).data
                onJoinRoom(generatedRoomCode)
                errorMessage = ""
            }
            is com.example.wishpchatroom.data.Result.Error -> {
                errorMessage = (roomResult as Result.Error).exception.message ?: "Operation failed"
            }
            null -> {
                errorMessage = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "WishP Chat Room",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        // Mode selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { isCreateMode = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Create Room")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { isCreateMode = false },
                modifier = Modifier.weight(1f)
            ) {
                Text("Join Room")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Username input
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )

        // Room code input (for join mode)
        if (!isCreateMode) {
            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it.uppercase() },
                label = { Text("Room Code") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }

        // Temporary room checkbox (for create mode)
        if (isCreateMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isTemporary,
                    onCheckedChange = { isTemporary = it }
                )
                Text(
                    text = "Temporary Room (auto-delete when creator leaves)",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Error message
        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = Color.Red,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Action button
        Button(
            onClick = {
                if (username.isNotBlank()) {
                    if (isCreateMode) {
                        roomViewModel.createRoom(username, isTemporary)
                    } else {
                        if (roomCode.isNotBlank()) {
                            roomViewModel.joinRoom(roomCode, username) { success ->
                                if (success) {
                                    onJoinRoom(roomCode)
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            enabled = username.isNotBlank() && (isCreateMode || roomCode.isNotBlank())
        ) {
            Text(if (isCreateMode) "Create & Join Room" else "Join Room")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Logout button
        OutlinedButton(
            onClick = {
                authViewModel.logout()
                onNavigateToLogin()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Text("Logout")
        }

        // User info
        currentUser?.let { user ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Logged in as: ${user.firstName} ${user.lastName}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}