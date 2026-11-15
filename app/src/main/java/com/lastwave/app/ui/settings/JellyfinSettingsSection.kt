package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JellyfinSessionStore
import com.lastwave.app.data.jellyfin.download.JellyDownloads
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JellyfinSettingsViewModel @Inject constructor(
    private val store: JellyfinSessionStore,
    private val downloads: JellyDownloads,
) : ViewModel() {
    val session: StateFlow<JellyfinSession?> =
        store.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val streamKbps: StateFlow<Int> = store.streamKbps.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val wifiOnly: StateFlow<Boolean> = downloads.wifiOnly.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoQuality: StateFlow<Boolean> = store.autoQuality.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val downloadKbps: StateFlow<Int> = store.downloadKbps.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val summary: StateFlow<Pair<Int, Long>> = downloads.summary

    fun setStreamKbps(kbps: Int) = viewModelScope.launch { store.setStreamKbps(kbps) }
    fun setAutoQuality(on: Boolean) = viewModelScope.launch { store.setAutoQuality(on) }
    fun setDownloadKbps(kbps: Int) = viewModelScope.launch { store.setDownloadKbps(kbps) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { downloads.setWifiOnly(on) }
    fun deleteAllDownloads() = viewModelScope.launch { downloads.removeAll() }
    fun signOut() = viewModelScope.launch { store.clear() }
}

private val QUALITIES = listOf(0 to "Original (no transcoding)", 320 to "320 kbps", 192 to "192 kbps", 128 to "128 kbps")

private fun qualityLabel(kbps: Int) = QUALITIES.firstOrNull { it.first == kbps }?.second ?: "$kbps kbps"

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}

/** Jellyfin account, streaming quality, download rules and storage. Shown at the top of Settings. */
@Composable
fun JellyfinSettingsSection(viewModel: JellyfinSettingsViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val kbps by viewModel.streamKbps.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val auto by viewModel.autoQuality.collectAsStateWithLifecycle()
    val downloadKbps by viewModel.downloadKbps.collectAsStateWithLifecycle()
    var pickQuality by remember { mutableStateOf(false) }
    var pickDownloadQuality by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Jellyfin")
        SettingsGroup(rowCount = 7) { index, position ->
            when (index) {
                0 -> SettingsActionCard(
                    icon = Icons.Filled.Dns, iconContainer = primary, iconTint = onPrimary,
                    title = session?.serverName?.ifBlank { null } ?: "Server",
                    subtitle = session?.let { "${it.serverUrl} • Jellyfin ${it.serverVersion}".trim() } ?: "Not signed in",
                    onClick = {}, position = position,
                )
                1 -> SettingsToggleCard(
                    icon = Icons.Filled.GraphicEq, iconContainer = primary, iconTint = onPrimary,
                    title = "Auto quality",
                    subtitle = "Original (lossless) when it helps; 320 kbps on mobile data or Bluetooth",
                    checked = auto, onCheckedChange = { viewModel.setAutoQuality(it) }, position = position,
                )
                2 -> SettingsActionCard(
                    icon = Icons.Filled.GraphicEq, iconContainer = primary, iconTint = onPrimary,
                    title = "Streaming quality",
                    subtitle = if (auto) "Chosen automatically" else qualityLabel(kbps),
                    onClick = { if (!auto) pickQuality = true }, position = position,
                )
                3 -> SettingsActionCard(
                    icon = Icons.Filled.Download, iconContainer = primary, iconTint = onPrimary,
                    title = "Download quality",
                    subtitle = if (downloadKbps == 0) "Original (highest)" else "MP3 at ${downloadKbps} kbps, new downloads only",
                    onClick = { pickDownloadQuality = true }, position = position,
                )
                4 -> SettingsToggleCard(
                    icon = Icons.Filled.Wifi, iconContainer = primary, iconTint = onPrimary,
                    title = "Download on Wi-Fi only", subtitle = "Pause downloads on mobile data",
                    checked = wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) }, position = position,
                )
                5 -> SettingsActionCard(
                    icon = Icons.Filled.Delete, iconContainer = primary, iconTint = onPrimary,
                    title = "Delete all downloads",
                    subtitle = "${summary.first} songs • ${sizeLabel(summary.second)}",
                    onClick = { if (summary.first > 0) confirmDelete = true }, position = position,
                )
                else -> SettingsActionCard(
                    icon = Icons.Filled.Logout, iconContainer = primary, iconTint = onPrimary,
                    title = "Sign out of Jellyfin", subtitle = "Downloads stay on this phone",
                    danger = true, onClick = { confirmSignOut = true }, position = position,
                )
            }
        }
    }

    if (pickQuality) {
        AlertDialog(
            onDismissRequest = { pickQuality = false },
            title = { Text("Streaming quality") },
            text = {
                Column {
                    Text(
                        "Applies to streaming on any network. Downloads always keep the original file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    QUALITIES.forEach { (value, label) ->
                        TextButton(onClick = { viewModel.setStreamKbps(value); pickQuality = false }) {
                            Text(if (value == kbps) "✓  $label" else label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickQuality = false }) { Text("Cancel") } },
        )
    }
    if (pickDownloadQuality) {
        AlertDialog(
            onDismissRequest = { pickDownloadQuality = false },
            title = { Text("Download quality") },
            text = {
                Column {
                    Text(
                        "Lower qualities are saved as MP3 to use less space. Songs already downloaded keep their quality.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    QUALITIES.forEach { (value, label) ->
                        TextButton(onClick = { viewModel.setDownloadKbps(value); pickDownloadQuality = false }) {
                            Text(if (value == downloadKbps) "\u2713  $label" else label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickDownloadQuality = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all downloads?") },
            text = { Text("Removes every downloaded album and playlist from this phone. Your Jellyfin library isn't touched.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteAllDownloads(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            confirmButton = { TextButton(onClick = { viewModel.signOut(); confirmSignOut = false }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
