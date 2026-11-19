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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.data.playlist.SpotifyUserPlaylist
import com.lastwave.app.data.requests.PlaylistLink
import com.lastwave.app.data.requests.PlaylistLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistLinksViewModel @Inject constructor(
    val links: PlaylistLinks,
    val jelly: JellyfinRepository,
    val spotify: SpotifyPlaylistImporter,
) : ViewModel()

private val DN_DIRS = listOf("OFF" to "Off", "TO" to "Jellyfin → server", "FROM" to "Server → Jellyfin", "BOTH" to "Both ways")

/** Saved playlist syncs: pick a Jellyfin playlist, link it to a server and/or Spotify playlist, set direction and rules. */
@Composable
internal fun PlaylistLinksDialog(onClose: () -> Unit, vm: PlaylistLinksViewModel = hiltViewModel()) {
    val links by vm.links.links.collectAsStateWithLifecycle()
    val status by vm.links.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PlaylistLink?>(null) }
    var auto by remember { mutableStateOf(vm.links.auto) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    editing?.let { e ->
        LinkEditor(e, vm, onDone = { editing = null })
        return
    }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Playlist syncs") },
        text = {
            Column {
                when {
                    running -> Text(status ?: "Syncing…")
                    result != null -> Text(result!!)
                    links.isEmpty() -> Text("No syncs yet. Add one to keep a Jellyfin playlist matched with your request server or a Spotify playlist.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(links, key = { it.id }) { l ->
                            Column(Modifier.fillMaxWidth().clickable { editing = l }.padding(vertical = 6.dp)) {
                                Text(l.jfName)
                                val parts = buildList {
                                    if (l.dnDir != "OFF") add("Server: " + DN_DIRS.first { it.first == l.dnDir }.second)
                                    if (l.spDir == "FROM") add("Spotify: ${l.spName ?: "playlist"} → Jellyfin")
                                    if (l.removals) add("removals sync")
                                }
                                Text(parts.joinToString(" · ").ifBlank { "Nothing linked" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                l.last?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync every 6 hours", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it; vm.links.auto = it })
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !running, onClick = { result = null; editing = PlaylistLink(java.util.UUID.randomUUID().toString(), "", "") }) { Text("Add") }
                TextButton(enabled = !running && links.isNotEmpty(), onClick = {
                    running = true; result = null
                    scope.launch { result = vm.links.runAll(); running = false }
                }) { Text("Sync now") }
            }
        },
        dismissButton = { TextButton(enabled = !running, onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun LinkEditor(start: PlaylistLink, vm: PlaylistLinksViewModel, onDone: () -> Unit) {
    var l by remember { mutableStateOf(start) }
    var jfLists by remember { mutableStateOf<List<JfItem>?>(null) }
    var spLists by remember { mutableStateOf<List<SpotifyUserPlaylist>?>(null) }
    val isNew = start.jfId.isBlank()
    LaunchedEffect(Unit) {
        jfLists = runCatching { vm.jelly.playlists().items }.getOrDefault(emptyList())
        spLists = runCatching { vm.spotify.fetchUserPlaylists() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "New playlist sync" else "Edit sync") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (isNew) {
                    item { Text("Jellyfin playlist", style = MaterialTheme.typography.labelLarge) }
                    items(jfLists.orEmpty(), key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { l = l.copy(jfId = p.id, jfName = p.name.orEmpty()) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = l.jfId == p.id, onCheckedChange = { l = l.copy(jfId = p.id, jfName = p.name.orEmpty()) })
                            Text(p.name.orEmpty())
                        }
                    }
                } else item { Text(l.jfName, style = MaterialTheme.typography.titleSmall) }
                item { Text("Request server playlist (same name)", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge) }
                item {
                    Column {
                        DN_DIRS.chunked(2).forEach { row ->
                            Row { row.forEach { (k, label) -> FilterChip(selected = l.dnDir == k, onClick = { l = l.copy(dnDir = k) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp)) } }
                        }
                    }
                }
                item { Text("Also read from a Spotify playlist", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge) }
                item {
                    Row(Modifier.fillMaxWidth().clickable { l = l.copy(spId = null, spName = null, spDir = "OFF") }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = l.spDir == "OFF", onCheckedChange = { l = l.copy(spId = null, spName = null, spDir = "OFF") }); Text("None")
                    }
                }
                items(spLists.orEmpty(), key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { l = l.copy(spId = p.id, spName = p.name, spDir = "FROM") }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = l.spId == p.id, onCheckedChange = { l = l.copy(spId = p.id, spName = p.name, spDir = "FROM") }); Text(p.name)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { l = l.copy(removals = !l.removals) }.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = l.removals, onCheckedChange = { l = l.copy(removals = it) })
                        Text("Songs removed from a source are removed from Jellyfin", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("Songs not in your library are kept in the playlist's “Unmatched songs” list. Spotify is read-only; the server can't remove songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(enabled = l.jfId.isNotBlank(), onClick = { vm.links.save(l); onDone() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { vm.links.delete(l.id); onDone() }) { Text("Delete") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}
