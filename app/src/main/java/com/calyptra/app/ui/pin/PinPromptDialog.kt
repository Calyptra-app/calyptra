package com.calyptra.app.ui.pin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.calyptra.app.R
import com.calyptra.app.ui.PinPromptState
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 4

/** Parent PIN dialog (PIN-L2 setup, PIN-L3 challenge) with lockout countdown. */
@Composable
fun PinPromptDialog(
    state: PinPromptState,
    onSubmitSetup: (String) -> Unit,
    onSubmitChallenge: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }

    var lockoutRemainingSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.lockedUntilMillis) {
        val until = state.lockedUntilMillis ?: 0L
        lockoutRemainingSec = (until - System.currentTimeMillis() + 999) / 1000
        while (lockoutRemainingSec > 0) {
            delay(1000)
            lockoutRemainingSec = (until - System.currentTimeMillis() + 999) / 1000
        }
    }
    val isLockedOut = lockoutRemainingSec > 0

    val title = if (state.isSetup) {
        stringResource(R.string.pin_setup_title)
    } else {
        stringResource(R.string.pin_challenge_title)
    }
    val message = when {
        state.isSetup && firstEntry == null -> stringResource(R.string.pin_setup_message)
        state.isSetup -> stringResource(R.string.pin_confirm_message)
        else -> stringResource(R.string.pin_challenge_message)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= PIN_LENGTH && value.all { it.isDigit() }) {
                            pin = value
                            mismatch = false
                        }
                    },
                    enabled = !isLockedOut,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )

                val error = when {
                    isLockedOut -> stringResource(R.string.pin_locked, lockoutRemainingSec)
                    mismatch -> stringResource(R.string.pin_mismatch)
                    state.attemptsLeft != null -> stringResource(R.string.pin_wrong, state.attemptsLeft)
                    else -> null
                }
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (!state.isSetup) {
                    Text(
                        stringResource(R.string.pin_forgot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length == PIN_LENGTH && !isLockedOut,
                onClick = {
                    if (state.isSetup) {
                        when {
                            firstEntry == null -> {
                                firstEntry = pin
                                pin = ""
                            }
                            firstEntry == pin -> onSubmitSetup(pin)
                            else -> {
                                mismatch = true
                                firstEntry = null
                                pin = ""
                            }
                        }
                    } else {
                        onSubmitChallenge(pin)
                        pin = ""
                    }
                }
            ) {
                Text(stringResource(R.string.pin_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pin_cancel))
            }
        }
    )
}
