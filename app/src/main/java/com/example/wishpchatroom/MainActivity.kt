package com.example.wishpchatroom

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wishpchatroom.navigation.Screen
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val navController = rememberNavController()
            val authViewModel: AuthViewModel = viewModel()
            WishpChatRoomTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavigationGraph(navController = navController, authViewModel = authViewModel)
                }
            }
        }
    }
}

@Composable
fun NavigationGraph(
    navController: NavHostController,
    authViewModel: AuthViewModel
) {
    val authResult by authViewModel.authResult.observeAsState()
    val currentUser by authViewModel.currentUser.observeAsState()

    LaunchedEffect(authResult) {
        authResult?.let { result ->
            if (result is com.example.wishpchatroom.data.Result.Success) {
                navController.navigate(Screen.ChatRoomsScreen.route) {
                    popUpTo(Screen.SignupScreen.route) { inclusive = true }
                    popUpTo(Screen.LoginScreen.route) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (currentUser != null) {
            navController.navigate(Screen.ChatRoomsScreen.route) {
                popUpTo(Screen.SignupScreen.route) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (currentUser != null) Screen.ChatRoomsScreen.route else Screen.SignupScreen.route
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
        composable("${Screen.ChatScreen.route}/{roomCode}") { backStackEntry ->
            val roomCode = backStackEntry.arguments?.getString("roomCode") ?: ""
            Log.d("Navigation", "In ChatScreen with room: $roomCode")
            ChatScreen(
                roomCode = roomCode,
                authViewModel = authViewModel,
                onNavigateBack = {
                    Log.d("Navigation", "Back button clicked - navigating back")
                    // Force navigate back to ChatRoomsScreen instead of just popping
                    navController.navigate(Screen.ChatRoomsScreen.route) {
                        popUpTo("${Screen.ChatScreen.route}/$roomCode") { inclusive = true }
                    }
                }
            )
        }
    }
}
