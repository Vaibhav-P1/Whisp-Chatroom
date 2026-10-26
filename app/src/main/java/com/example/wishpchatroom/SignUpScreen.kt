package com.example.wishpchatroom

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
fun SignUpScreen(
    authViewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }

    val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val canSubmit = firstName.isNotBlank() && lastName.isNotBlank() &&
            email.isNotBlank() && password.isNotBlank()

    // Do not show an error left over from the other auth screen.
    LaunchedEffect(Unit) { authViewModel.clearError() }

    AuthScreenLayout {
        AuthHeader(
            icon = {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            },
            title = stringResource(R.string.create_account),
            subtitle = stringResource(R.string.auth_signup_subtitle)
        )

        AuthFormCard(fieldSpacing = 16.dp) {
            AuthTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = stringResource(R.string.first_name),
                icon = Icons.Default.Person
            )

            AuthTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = stringResource(R.string.last_name),
                icon = Icons.Default.Person
            )

            AuthTextField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.email_address),
                icon = Icons.Default.Email
            )

            AuthPasswordField(
                value = password,
                onValueChange = { password = it }
            )

            uiState.errorMessage?.let { ErrorCard(message = it) }

            Spacer(modifier = Modifier.height(8.dp))

            LoadingButton(
                text = stringResource(R.string.create_account),
                isLoading = uiState.isLoading,
                enabled = !uiState.isLoading && canSubmit,
                onClick = {
                    if (canSubmit) authViewModel.signUp(email, password, firstName, lastName)
                }
            )
        }

        AuthFooterLink(
            prompt = stringResource(R.string.auth_have_account),
            action = stringResource(R.string.sign_in),
            onClick = onNavigateToLogin,
            topPadding = 24.dp
        )
    }
}
