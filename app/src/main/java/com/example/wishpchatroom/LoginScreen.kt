package com.example.wishpchatroom

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wishpchatroom.ui.components.AuthFooterLink
import com.example.wishpchatroom.ui.components.AuthFormCard
import com.example.wishpchatroom.ui.components.AuthHeader
import com.example.wishpchatroom.ui.components.AuthPasswordField
import com.example.wishpchatroom.ui.components.AuthScreenLayout
import com.example.wishpchatroom.ui.components.AuthTextField
import com.example.wishpchatroom.ui.components.ErrorCard
import com.example.wishpchatroom.ui.components.LoadingButton
import com.example.wishpchatroom.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onNavigateToSignUp: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val canSubmit = email.isNotBlank() && password.isNotBlank()

    // Do not show an error left over from the other auth screen.
    LaunchedEffect(Unit) { authViewModel.clearError() }

    AuthScreenLayout {
        AuthHeader(
            icon = { Text(text = "💬", fontSize = 40.sp) },
            title = "Welcome Back!",
            subtitle = "Sign in to continue to WishP Chat",
            large = true
        )

        AuthFormCard(fieldSpacing = 20.dp) {
            AuthTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email Address",
                icon = Icons.Default.Email
            )

            AuthPasswordField(
                value = password,
                onValueChange = { password = it }
            )

            uiState.errorMessage?.let { ErrorCard(message = it) }

            Spacer(modifier = Modifier.height(8.dp))

            LoadingButton(
                text = "Sign In",
                isLoading = uiState.isLoading,
                enabled = !uiState.isLoading && canSubmit,
                onClick = { if (canSubmit) authViewModel.login(email, password) }
            )
        }

        AuthFooterLink(
            prompt = "Don't have an account?",
            action = "Sign Up",
            onClick = onNavigateToSignUp,
            topPadding = 32.dp
        )
    }
}
