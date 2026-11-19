package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.listenbrainz.ListenBrainzClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListenBrainzViewModel @Inject constructor(val client: ListenBrainzClient) : ViewModel()

/** ListenBrainz scrobbling: connect with a user token, or disconnect. */
@Composable
fun ListenBrainzSettingsSection(vm: ListenBrainzViewModel = hiltViewModel()) {
    val user by vm.client.user.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("ListenBrainz")
        SettingsGroup(rowCount = if (user != null) 2 else 1) { index, position ->
            when (index) {
                0 -> SettingsActionCard(
                    icon = Icons.Filled.GraphicEq, iconContainer = primary, iconTint = onPrimary,
                    title = "Scrobble to ListenBrainz",
                    subtitle = user?.let { "Connected as $it" } ?: "Not connected. Plays are saved to your MusicBrainz listening history.",
                    onClick = { connecting = true }, position = position,
                )
                else -> SettingsActionCard(
                    icon = Icons.Filled.Logout, iconContainer = primary, iconTint = onPrimary,
                    title = "Disconnect", subtitle = "Stop scrobbling and forget the token",
                    danger = true, onClick = { vm.client.disconnect() }, position = position,
                )
            }
        }
    }
    if (connecting) {
        val scope = rememberCoroutineScope()
        val uri = LocalUriHandler.current
        var token by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { connecting = false },
            title = { Text("ListenBrainz") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste your user token from your ListenBrainz settings page.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching { uri.openUri("https://listenbrainz.org/settings/") } }) { Text("Open listenbrainz.org/settings") }
                    OutlinedTextField(
                        token, { token = it }, label = { Text("User token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && token.isNotBlank(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val err = vm.client.connect(token)
                            busy = false
                            if (err == null) connecting = false else error = err
                        }
                    },
                ) { Text(if (busy) "Checking…" else "Connect") }
            },
            dismissButton = { TextButton(onClick = { connecting = false }) { Text("Cancel") } },
        )
    }
}
