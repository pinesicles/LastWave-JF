package com.lastwave.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.data.requests.PlaylistSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistSyncViewModel @Inject constructor(val sync: PlaylistSync) : ViewModel()

/** Pick Jellyfin playlists and copy them to the request server. */
@Composable
internal fun PlaylistSyncDialog(onClose: () -> Unit, vm: PlaylistSyncViewModel = hiltViewModel()) {
    var playlists by remember { mutableStateOf<List<JfItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val status by vm.sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val list = vm.sync.jellyfinPlaylists()
            playlists = list
            picked = list.map { it.id }.toSet()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read your Jellyfin playlists"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Copy playlists to request server") },
        text = {
            Column {
                val list = playlists
                when {
                    result != null -> Text(result!!)
                    running -> Text(status ?: "Working…")
                    error != null -> Text(error!!)
                    list == null -> Text("Loading…")
                    list.isEmpty() -> Text("No Jellyfin playlists.")
                    else -> {
                        Text(
                            "Adds songs your server playlist doesn't have yet, and creates playlists that don't exist there. Nothing is removed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(list, key = { it.id }) { p ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { picked = if (p.id in picked) picked - p.id else picked + p.id }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = p.id in picked, onCheckedChange = { picked = if (it) picked + p.id else picked - p.id })
                                    Text(p.name.orEmpty())
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (result != null) TextButton(onClick = onClose) { Text("Done") }
            else TextButton(
                enabled = !running && picked.isNotEmpty() && playlists != null,
                onClick = {
                    running = true
                    scope.launch {
                        result = vm.sync.copy(playlists.orEmpty().filter { it.id in picked })
                        running = false
                    }
                },
            ) { Text(if (running) "Copying…" else "Copy ${picked.size}") }
        },
        dismissButton = { if (!running && result == null) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
