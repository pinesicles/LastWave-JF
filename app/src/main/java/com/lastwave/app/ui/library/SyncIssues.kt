package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.likes.LikeMirror
import com.lastwave.app.data.likes.LikeNode
import com.lastwave.app.data.likes.MatchCandidate
import com.lastwave.app.data.likes.SyncFailure
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.requests.LocalRequestAlbum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncIssuesViewModel @Inject constructor(val mirror: LikeMirror) : ViewModel()

/** A sync icon with a count that appears when a likes sync left songs it couldn't place. Tap to sort them out. */
@Composable
internal fun SyncIssuesButton(vm: SyncIssuesViewModel = hiltViewModel(key = "sync-issues")) {
    val failures by vm.mirror.failures.collectAsStateWithLifecycle()
    if (failures.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    BadgedBox(badge = { Badge { Text(failures.size.coerceAtMost(99).toString()) } }) {
        HeaderActionIcon(Icons.Filled.Sync, "Songs that didn't sync", { open = true })
    }
    if (open) SyncIssuesDialog(vm.mirror, failures) { open = false }
}

/** The sync-issues dialog, opened from the Liked songs menu. */
@Composable
internal fun SyncIssuesHost(vm: SyncIssuesViewModel = hiltViewModel(key = "sync-issues"), onClose: () -> Unit) {
    val failures by vm.mirror.failures.collectAsStateWithLifecycle()
    SyncIssuesDialog(vm.mirror, failures, onClose)
}

@Composable
private fun SyncIssuesDialog(mirror: LikeMirror, failures: List<SyncFailure>, onClose: () -> Unit) {
    val request = LocalRequestAlbum.current
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picking by remember { mutableStateOf<SyncFailure?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Songs that didn't sync") },
        text = {
            if (failures.isEmpty()) Text(note ?: "All sorted.") else Column {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "These liked songs couldn't be matched on one service. Pick the right song by hand, request it, or ignore it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(failures, key = { it.key }) { f ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(f.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${f.track.artist} • not found on ${f.node.label}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                if (f.node == LikeNode.JELLYFIN || f.node == LikeNode.SPOTIFY) {
                                    TextButton(onClick = { picking = f }) { Text("Choose match") }
                                }
                                if (f.node == LikeNode.JELLYFIN) {
                                    TextButton(onClick = { request(listOf(f.track.artist, f.track.album ?: f.track.title).joinToString(" ")) }) { Text("Request") }
                                }
                                TextButton(onClick = { mirror.ignore(f) }) { Text("Ignore") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(enabled = !searching && failures.isNotEmpty(), onClick = {
                searching = true
                scope.launch {
                    val n = mirror.researchAll()
                    note = if (n > 0) "Found and liked $n in your library" else "Nothing new found in your library"
                    searching = false
                }
            }) { Text(if (searching) "Searching…" else "Search library again") }
        },
    )
    picking?.let { f -> MatchPicker(mirror, f) { picking = null } }
}

@Composable
private fun MatchPicker(mirror: LikeMirror, f: SyncFailure, onClose: () -> Unit) {
    var query by remember { mutableStateOf(if (f.node == LikeNode.JELLYFIN) f.track.title else "${f.track.artist} ${f.track.title}") }
    var results by remember { mutableStateOf<List<MatchCandidate>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        delay(450)
        results = null
        results = mirror.candidates(f.node, query)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pick the match on ${f.node.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Search") })
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val list = results
                when {
                    list == null -> Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() -> Text("Nothing found. Try different words.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list, key = { it.id }) { c ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        if (mirror.chooseMatch(f, c)) onClose() else msg = "Couldn't add that one"
                                    }
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(c.artist.ifBlank { null }, c.album).joinToString(" • "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
