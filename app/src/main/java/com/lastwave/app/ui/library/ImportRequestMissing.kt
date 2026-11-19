package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.importer.ImportMatcher
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.ui.requests.RequestsViewModel
import kotlinx.coroutines.launch

/** Songs an import couldn't find, gathered under the album (or just the artist) they came from. */
internal class MissingGroup(val artist: String, val album: String?, val songs: List<ImportTrack>) {
    val key: String get() = "${ImportMatcher.norm(artist)}|${ImportMatcher.norm(album.orEmpty())}"
}

internal fun groupMissing(unmatched: List<JellyImportViewModel.Unmatched>): List<MissingGroup> =
    unmatched.filter { !it.added }.map { it.source }
        .groupBy { "${ImportMatcher.norm(it.artist)}|${ImportMatcher.norm(it.album.orEmpty())}" }
        .values
        .map { songs -> MissingGroup(songs.first().artist, songs.first().album?.takeIf { it.isNotBlank() }, songs) }
        .sortedWith(compareBy({ it.album == null }, { -it.songs.size }))

/**
 * "Request missing albums": the songs an import couldn't find, grouped by album, each one a request on
 * your request server. Songs with no album can't be requested as a whole album, so they open a manual search.
 */
@Composable
internal fun RequestMissingDialog(
    unmatched: List<JellyImportViewModel.Unmatched>,
    onClose: () -> Unit,
    /** Called when an album's request went through (or it's already on its way), so callers can drop it from their list. */
    onRequested: (MissingGroup) -> Unit = {},
    /** When set, a "Re-check" button runs it. */
    onRecheck: (() -> Unit)? = null,
) {
    val okResults = setOf("Queued", "Downloading", "Waiting for approval", "Added to library", "In library", "Already requested")
    val vm: RequestsViewModel = hiltViewModel(key = "requests-global")
    val config by vm.config.collectAsStateWithLifecycle()
    val groups = remember(unmatched) { groupMissing(unmatched) }
    val results = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val search = com.lastwave.app.ui.requests.LocalRequestAlbum.current
    var running by remember { mutableStateOf(false) }
    val albumGroups = groups.filter { it.album != null }

    fun requestOne(g: MissingGroup) {
        results[g.key] = "Requesting…"
        scope.launch {
            results[g.key] = vm.requestBestMatch(g.artist, g.album.orEmpty())
            if (results[g.key] in okResults) onRequested(g)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Request missing albums") },
        text = {
            if (config == null) {
                Text("Connect your request server first: Settings → Request server.")
            } else if (groups.isEmpty()) {
                Text("Nothing missing.")
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${unmatched.count { !it.added }} songs from ${groups.size} albums and artists weren't found.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(groups, key = { it.key }) { g ->
                        val result = results[g.key]
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.album ?: "No album listed", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${g.artist} • ${g.songs.size} song${if (g.songs.size == 1) "" else "s"}: " + g.songs.joinToString(", ") { it.title },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                result != null -> Text(result, style = MaterialTheme.typography.labelMedium)
                                g.album != null -> TextButton(enabled = !running, onClick = { requestOne(g) }) { Text("Request") }
                                else -> TextButton(onClick = { search("${g.artist} ${g.songs.first().title}") }) { Text("Search") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (config != null && albumGroups.any { results[it.key] == null }) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch {
                            for (g in albumGroups) {
                                if (results[g.key] != null) continue
                                results[g.key] = "Requesting…"
                                results[g.key] = vm.requestBestMatch(g.artist, g.album.orEmpty())
                                if (results[g.key] in okResults) onRequested(g)
                            }
                            running = false
                        }
                    },
                ) { Text(if (running) "Requesting…" else "Request all albums") }
            } else {
                TextButton(onClick = onClose) { Text("Done") }
            }
        },
        dismissButton = {
            Row {
                if (onRecheck != null) TextButton(onClick = onRecheck) { Text("Re-check") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
