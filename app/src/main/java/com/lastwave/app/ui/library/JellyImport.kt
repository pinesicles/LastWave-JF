package com.lastwave.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.theme.ExpressivePillShape
import kotlinx.coroutines.delay

/**
 * Full-screen playlist import: pick a CSV/JSON file or paste a link, then see what matched
 * your Jellyfin library and fix the rest by hand.
 */
@Composable
fun JellyImportDialog(viewModel: JellyImportViewModel, onClose: (created: Boolean) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val created = state.phase == JellyImportViewModel.Phase.DONE
    val close = { onClose(created); viewModel.reset() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.unmatchedCsv().toByteArray()) }
            }
        }
    }
    var searchIndex by remember { mutableStateOf<Int?>(null) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Import playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                val searching = searchIndex?.let { state.unmatched.getOrNull(it) }
                if (searching != null) {
                    ManualSearchPanel(
                        initial = searching.source.title,
                        subtitle = listOfNotNull(searching.source.artist.takeIf { it.isNotBlank() }, searching.source.album).joinToString(" \u2022 "),
                        viewModel = viewModel,
                        onPick = { viewModel.addManual(searchIndex!!, it); searchIndex = null },
                        onBack = { searchIndex = null },
                    )
                } else when (state.phase) {
                    JellyImportViewModel.Phase.IDLE -> ImportForm(state, viewModel) { filePicker.launch("*/*") }
                    JellyImportViewModel.Phase.WORKING -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state.total == 0) CircularProgressIndicator() else {
                            LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            Text("Matching ${state.done} of ${state.total} songs…")
                        }
                    }
                    JellyImportViewModel.Phase.DONE -> ImportResult(
                        state = state,
                        onExport = { exporter.launch("unmatched-songs.csv") },
                        onSearch = { searchIndex = it },
                        onPickSuggestion = viewModel::addManual,
                        onDone = close,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportForm(state: JellyImportViewModel.UiState, viewModel: JellyImportViewModel, onPickFile: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var showKeys by remember { mutableStateOf(false) }
    var keys by remember(state.keys) { mutableStateOf(state.keys) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Songs are matched against your Jellyfin library, then a new playlist is created. Anything that can't be matched is listed so you can search for it or export it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            singleLine = true,
            label = { Text("Playlist name (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
        val spotifyPlaylists by viewModel.spotifyPlaylists.collectAsStateWithLifecycle()
        LaunchedEffect(spotifyConnected) { if (spotifyConnected) viewModel.loadSpotifyPlaylists() }
        Text("Your Spotify", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (spotifyConnected) {
            TextButton(onClick = { viewModel.importSpotifyLiked() }) { Text("Liked Songs") }
            spotifyPlaylists.forEach { p ->
                TextButton(onClick = { viewModel.importSpotifyPlaylist(p) }) {
                    Text(
                        p.name + (p.trackCount?.let { " ($it)" } ?: ""),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(
                "Sign in to Spotify in Settings to import your own playlists and Liked Songs here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onPickFile, shape = ExpressivePillShape, modifier = Modifier.fillMaxWidth()) {
            Text("Choose a CSV or JSON file")
        }
        Text("or paste a link", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            label = { Text("Spotify or YouTube playlist link") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { viewModel.importLink(link) },
            enabled = link.isNotBlank(),
            shape = ExpressivePillShape,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import link") }
        Text(
            "Apple Music and Tidal can't be read directly: export the playlist to CSV with a converter and choose that file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Hide keys for link import" else "Keys for link import") }
        if (showKeys) {
            Text(
                "YouTube links need a free YouTube Data API key (console.cloud.google.com). Spotify uses the account you signed in with in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                keys.youtube, { keys = keys.copy(youtube = it) }, singleLine = true,
                label = { Text("YouTube API key") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.saveKeys(keys) }, shape = ExpressivePillShape) { Text("Save keys") }
        }
    }
}

@Composable
private fun ImportResult(
    state: JellyImportViewModel.UiState,
    onExport: () -> Unit,
    onSearch: (Int) -> Unit,
    onPickSuggestion: (Int, PlayableTrack) -> Unit,
    onDone: () -> Unit,
) {
    val total = state.matched + state.unmatched.count { !it.added }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Created “${state.playlistName}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${state.matched} of $total songs added" + if (state.unmatched.any { !it.added }) " · ${state.unmatched.count { !it.added }} not found" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone, shape = ExpressivePillShape) { Text("Done") }
                if (state.unmatched.any { !it.added }) {
                    OutlinedButton(onClick = onExport, shape = ExpressivePillShape) { Text("Export not found (CSV)") }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.unmatched.size) { index ->
                val u = state.unmatched[index]
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(u.source.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(u.source.artist.takeIf { it.isNotBlank() }, u.source.album).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (u.added) {
                            Text("Added", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        } else {
                            u.suggestions.forEach { s ->
                                TextButton(onClick = { onPickSuggestion(index, s) }) {
                                    Text("Add: ${s.title} – ${s.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = { onSearch(index) }) { Text("Search library…") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSearchPanel(
    initial: String,
    subtitle: String,
    viewModel: JellyImportViewModel,
    onPick: (PlayableTrack) -> Unit,
    onBack: () -> Unit,
) {
    var term by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<PlayableTrack>>(emptyList()) }
    LaunchedEffect(term) {
        loading = true
        delay(300)
        viewModel.manualSearch(term)
            .onSuccess { results = it; error = null }
            .onFailure { results = emptyList(); error = it.message ?: "Search failed" }
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Find in your library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            term, { term = it }, singleLine = true, label = { Text("Song title") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        TextButton(onClick = onBack) { Text("Back to list") }
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text("Search failed: $error", color = MaterialTheme.colorScheme.error)
            results.isEmpty() -> Text(
                "No songs found for \u201c${term.trim()}\u201d. Try fewer words or just the main title.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.internalTrackId ?: it.title }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(t.artist, t.album).joinToString(" \u2022 "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Hosts the Jellyfin import as a navigation destination (used by the Settings import entries). */
@Composable
fun JellyImportRoute(onClose: () -> Unit) {
    JellyImportDialog(androidx.hilt.navigation.compose.hiltViewModel()) { onClose() }
}
