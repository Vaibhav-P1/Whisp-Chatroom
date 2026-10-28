package com.example.wishpchatroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wishpchatroom.ui.components.AuthPasswordField
import com.example.wishpchatroom.ui.components.AuthTextField
import com.example.wishpchatroom.ui.components.ErrorCard

/** Asks for an email, or confirms that the reset mail was requested once [sent] is true. */
@Composable
fun ForgotPasswordDialog(
    initialEmail: String,
    isLoading: Boolean,
    errorMessage: String?,
    sent: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf(initialEmail) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_password_title)) },
        text = {
            if (sent) {
                Text(stringResource(R.string.reset_password_sent))
            } else {
                Column {
                    Text(stringResource(R.string.reset_password_message))
                    Spacer(modifier = Modifier.height(16.dp))
                    AuthTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = stringResource(R.string.email_address),
                        icon = Icons.Default.Email
                    )
                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        ErrorCard(message = it)
                    }
                }
            }
        },
        confirmButton = {
            if (sent) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            } else {
                TextButton(onClick = { onSend(email) }, enabled = !isLoading && email.isNotBlank()) {
                    Text(stringResource(R.string.reset_password_send))
                }
            }
        },
        dismissButton = {
            if (!sent) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Final confirmation for account deletion; the password re-authenticates the user. */
@Composable
fun DeleteAccountDialog(
    isDeleting: Boolean,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_account_message))
                Spacer(modifier = Modifier.height(16.dp))
                AuthPasswordField(value = password, onValueChange = { password = it })
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    ErrorCard(message = it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = !isDeleting && password.isNotEmpty()) {
                Text(stringResource(R.string.delete_account_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text(stringResource(R.string.cancel)) }
        }
    )
}
