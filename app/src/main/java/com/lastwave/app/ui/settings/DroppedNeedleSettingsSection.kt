package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.requests.DroppedNeedleConnectDialog
import com.lastwave.app.ui.requests.DroppedNeedleRequestsDialog
import com.lastwave.app.ui.requests.RequestsViewModel

/** Request server (DroppedNeedle): connect, see your requests, disconnect. Sits under the Jellyfin section. */
@Composable
fun DroppedNeedleSettingsSection(vm: RequestsViewModel = hiltViewModel(key = "requests-global")) {
    val config by vm.config.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer
    val connected = config != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Request server")
        SettingsGroup(rowCount = if (connected) 3 else 1) { index, position ->
            when (index) {
                0 -> SettingsActionCard(
                    icon = Icons.Filled.CloudDownload, iconContainer = primary, iconTint = onPrimary,
                    title = "DroppedNeedle",
                    subtitle = config?.let { "${it.url} • ${it.user}${if (it.role.isNotBlank()) " (${it.role})" else ""}" }
                        ?: "Not connected. Request albums that aren't in your library.",
                    onClick = { connecting = true }, position = position,
                )
                1 -> SettingsActionCard(
                    icon = Icons.Filled.Download, iconContainer = primary, iconTint = onPrimary,
                    title = "Your requests", subtitle = "Progress of albums you've asked for",
                    onClick = { listing = true }, position = position,
                )
                else -> SettingsActionCard(
                    icon = Icons.Filled.Logout, iconContainer = primary, iconTint = onPrimary,
                    title = "Disconnect", subtitle = "Forget the server login on this phone",
                    danger = true, onClick = { vm.disconnect() }, position = position,
                )
            }
        }
    }
    if (connecting) DroppedNeedleConnectDialog(vm) { connecting = false }
    if (listing) DroppedNeedleRequestsDialog(vm) { listing = false }
}
