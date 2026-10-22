package dev.system.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.system.app.R
import dev.system.app.data.SystemRepository
import kotlinx.coroutines.launch

@Composable
internal fun SignInScreen(repository: SystemRepository) {
    val scope = rememberCoroutineScope()
    val authCallbackError by repository.authCallbackError.collectAsState()
    var email by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "A clear head starts here.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Your tasks, habits, and goals in one calm place.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(if (sent) "Check your inbox" else "Continue with email", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sent) "A secure sign-in link was sent to " + email + ". Open it on this Android device to finish signing in. Check your spam folder if it isn’t there."
                        else stringResource(R.string.sign_in_account_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                    )

                    if (!sent) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; message = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = repository.isConfigured && !busy && email.trim().contains("@"),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            onClick = {
                                busy = true
                                message = ""
                                repository.clearAuthCallbackError()
                                scope.launch {
                                    runCatching { repository.requestEmailLink(email) }
                                        .onSuccess { sent = true; message = "Sign-in link sent. It may take a minute to arrive."; messageIsError = false }
                                        .onFailure { message = repository.signInRequestFailureMessage(it); messageIsError = true }
                                    busy = false
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Email me a sign-in link")
                        }
                        if (!repository.isConfigured) {
                            Text(
                                "Connect Supabase to enable sign-in. Add the project URL and publishable key to android/local.properties.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    } else {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            onClick = {
                                busy = true
                                message = ""
                                scope.launch {
                                    runCatching { repository.requestEmailLink(email) }
                                        .onSuccess { message = "A fresh sign-in link is on its way."; messageIsError = false }
                                        .onFailure { message = repository.signInRequestFailureMessage(it); messageIsError = true }
                                    busy = false
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Resend sign-in link")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !busy,
                                onClick = { sent = false; message = ""; repository.clearAuthCallbackError() },
                            ) { Text("Change email") }
                        }
                    }

                    if (!authCallbackError.isNullOrBlank()) {
                        Text(
                            authCallbackError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
