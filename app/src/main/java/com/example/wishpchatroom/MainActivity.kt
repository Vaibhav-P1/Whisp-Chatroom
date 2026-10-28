package com.example.wishpchatroom

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wishpchatroom.data.isDmRoom
import com.example.wishpchatroom.navigation.Screen
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.AuthViewModel
import com.example.wishpchatroom.viewmodel.ChatViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val navController = rememberNavController()
            val authViewModel: AuthViewModel = hiltViewModel()
            // wishp://join/CODE opens the join form with the room code filled in.
            val joinCode = remember { intent?.data?.takeIf { it.scheme == "wishp" && it.host == "join" }?.lastPathSegment }
            WishpChatRoomTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavigationGraph(navController = navController, authViewModel = authViewModel, joinCode = joinCode)
                }
            }
        }
    }
}

@Composable
fun NavigationGraph(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    joinCode: String? = null
) {
    // Decided once, synchronously, so a logged-in user never sees the signup screen on cold start.
    val startDestination = remember {
        if (authViewModel.isLoggedIn()) Screen.ChatRoomsScreen.route else Screen.SignupScreen.route
    }

    LaunchedEffect(Unit) {
        authViewModel.authenticated.collect {
            navController.navigate(Screen.ChatRoomsScreen.route) {
                popUpTo(Screen.SignupScreen.route) { inclusive = true }
                popUpTo(Screen.LoginScreen.route) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.SignupScreen.route) {
            SignUpScreen(
                authViewModel = authViewModel,
                onNavigateToLogin = {
                    Log.d("Navigation", "Navigating to Login")
                    navController.navigate(Screen.LoginScreen.route)
                }
            )
        }
        composable(Screen.LoginScreen.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onNavigateToSignUp = {
                    Log.d("Navigation", "Navigating to SignUp")
                    navController.navigate(Screen.SignupScreen.route)
                }
            )
        }
        composable(Screen.ChatRoomsScreen.route) {
            ChatRoomScreen(
                authViewModel = authViewModel,
                initialJoinCode = joinCode,
                onOpenNotes = { navController.navigate(Screen.NotesScreen.route) },
                onOpenInbox = { navController.navigate(Screen.InboxScreen.route) },
                onOpenFriends = { navController.navigate(Screen.ContactsScreen.route) },
                onJoinRoom = { roomCode ->
                    Log.d("Navigation", "Joining room: $roomCode")
                    navController.navigate("${Screen.ChatScreen.route}/$roomCode")
                },
                onNavigateToLogin = {
                    Log.d("Navigation", "Logging out, navigating to Login")
                    navController.navigate(Screen.LoginScreen.route) {
                        popUpTo(Screen.ChatRoomsScreen.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.InboxScreen.route) {
            InboxScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { roomCode -> navController.navigate("${Screen.ChatScreen.route}/$roomCode") },
                onOpenFriends = { navController.navigate(Screen.ContactsScreen.route) }
            )
        }
        composable(Screen.NotesScreen.route) {
            SavedNotesScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.ContactsScreen.route) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { roomCode -> navController.navigate("${Screen.ChatScreen.route}/$roomCode") }
            )
        }
        composable("${Screen.ChatScreen.route}/{roomCode}") { backStackEntry ->
            val roomCode = backStackEntry.arguments?.getString("roomCode") ?: ""
            Log.d("Navigation", "In ChatScreen with room: $roomCode")
            ChatScreenWithLifecycle(
                roomCode = roomCode,
                onNavigateBack = {
                    Log.d("Navigation", "Back button clicked - navigating back")
                    // A DM was opened from the inbox or friends list: go back to where it came from.
                    if (isDmRoom(roomCode) && navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.ChatRoomsScreen.route) {
                            popUpTo("${Screen.ChatScreen.route}/$roomCode") { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ChatScreenWithLifecycle(
    roomCode: String,
    onNavigateBack: () -> Unit
) {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Presence follows the app lifecycle. Temporary rooms are NOT deleted here: backgrounding
    // or rotating the app is not leaving the room. Deletion happens on an explicit leave.
    DisposableEffect(roomCode, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> chatViewModel.updateUserPresence(roomCode, false)
                Lifecycle.Event.ON_RESUME -> chatViewModel.updateUserPresence(roomCode, true)
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ChatScreen(
        roomCode = roomCode,
        onNavigateBack = onNavigateBack
    )
}
