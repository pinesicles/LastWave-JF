package com.lastwave.app.ui.requests

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.requests.DnAlbum
import com.lastwave.app.data.requests.DnConfig
import com.lastwave.app.data.requests.DnException
import com.lastwave.app.data.requests.DnRequest
import com.lastwave.app.data.requests.DroppedNeedleClient
import com.lastwave.app.data.requests.dnStatusLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Call with a search phrase (e.g. "Artist Album") to open the "Request on server" dialog. */
internal val LocalRequestAlbum = staticCompositionLocalOf<(String) -> Unit> { {} }

data class RequestsUi(
    val searching: Boolean = false,
    val albums: List<DnAlbum> = emptyList(),
    val sent: Map<String, String> = emptyMap(),
    val error: String? = null,
    val requests: List<DnRequest>? = null,
)

@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val client: DroppedNeedleClient,
    private val watcher: com.lastwave.app.data.requests.RequestWatcher,
    private val likeMirror: com.lastwave.app.data.likes.LikeMirror,
) : ViewModel() {
    init { watcher.start(); likeMirror.autoSyncIfDue() }

    val config: StateFlow<DnConfig?> = client.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _ui = MutableStateFlow(RequestsUi())
    val ui: StateFlow<RequestsUi> = _ui.asStateFlow()

    fun search(query: String) {
        _ui.update { it.copy(searching = true, error = null, albums = emptyList()) }
        viewModelScope.launch {
            try {
                val found = client.searchAlbums(query)
                _ui.update { it.copy(searching = false, albums = found) }
            } catch (e: DnException) {
                _ui.update { it.copy(searching = false, error = e.message) }
            }
        }
    }

    fun request(album: DnAlbum) {
        viewModelScope.launch {
            try {
                val status = client.requestAlbum(album)
                watcher.watch(album)
                _ui.update { it.copy(sent = it.sent + (album.mbid to status)) }
            } catch (e: DnException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    private val _shelves = MutableStateFlow<List<com.lastwave.app.data.requests.DnShelf>>(emptyList())
    val shelves: StateFlow<List<com.lastwave.app.data.requests.DnShelf>> = _shelves.asStateFlow()
    private var shelvesLoaded = false

    /** Loads the Discover shelves once per app session, and only when the request server is connected. */
    fun loadDiscover() {
        if (shelvesLoaded || config.value == null) return
        shelvesLoaded = true
        viewModelScope.launch {
            try {
                _shelves.value = client.discover()
            } catch (e: DnException) {
                shelvesLoaded = false
            }
        }
    }

    /** Requests this album directly (no search). [onDone] gets a short result label. */
    fun requestDirect(album: DnAlbum, onDone: (String) -> Unit) {
        viewModelScope.launch {
            onDone(
                try { dnStatusLabel(client.requestAlbum(album).also { watcher.watch(album) }) } catch (e: DnException) { e.message ?: "Failed" },
            )
        }
    }

    /** Requests the catalogue album that best matches [artist] and [album]. Returns a short result for the list. */
    suspend fun requestBestMatch(artist: String, album: String): String {
        val norm = { s: String -> com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(s) }
        return try {
            val hits = client.searchAlbums("$artist $album".trim())
            val wantArtist = norm(artist)
            val best = hits.firstOrNull { h ->
                norm(h.title) == norm(album) &&
                    (wantArtist.isBlank() || norm(h.artist).let { a -> a.contains(wantArtist) || wantArtist.contains(a) })
            } ?: return "No match"
            when {
                best.inLibrary -> "In library"
                best.requested -> "Already requested"
                else -> dnStatusLabel(client.requestAlbum(best).also { watcher.watch(best) })
            }
        } catch (e: DnException) {
            e.message ?: "Failed"
        }
    }

    fun loadRequests() {
        _ui.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                _ui.update { it.copy(requests = client.myRequests()) }
            } catch (e: DnException) {
                _ui.update { it.copy(requests = emptyList(), error = e.message) }
            }
        }
    }

    fun connect(url: String, user: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            done(try { client.connect(url, user, password); null } catch (e: DnException) { e.message })
        }
    }

    fun disconnect() = viewModelScope.launch { client.disconnect() }
}

/** App-wide "Request on server" dialog. [query] is the phrase to search for; null means closed. */
@Composable
fun RequestAlbumHost(query: String?, onClose: () -> Unit) {
    if (query == null) return
    val vm: RequestsViewModel = hiltViewModel(key = "requests-global")
    val config by vm.config.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var text by remember(query) { mutableStateOf(query) }
    LaunchedEffect(query, config != null) { if (config != null && query.isNotBlank()) vm.search(query) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Request on your server") },
        text = {
            if (config == null) {
                Text("Connect your request server first: Settings → Request server.")
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Album or artist") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { vm.search(text) }) { Text("Search") } },
                )
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (ui.searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(ui.albums, key = { it.mbid }) { a ->
                        val sent = ui.sent[a.mbid]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(a.artist.ifBlank { null }, a.year?.toString()).joinToString(" • "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                sent != null -> Text(dnStatusLabel(sent), style = MaterialTheme.typography.labelMedium)
                                a.inLibrary -> Text("In library", style = MaterialTheme.typography.labelMedium)
                                a.requested -> Text("Requested", style = MaterialTheme.typography.labelMedium)
                                else -> TextButton(onClick = { vm.request(a) }) { Text("Request") }
                            }
                        }
                    }
                }
                if (!ui.searching && ui.albums.isEmpty() && ui.error == null) {
                    Text("No albums found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** Sign-in form for the request server. */
@Composable
fun DroppedNeedleConnectDialog(vm: RequestsViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Request server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your DroppedNeedle address and login.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                onClick = {
                    busy = true; error = null
                    vm.connect(url, user, pass) { err ->
                        busy = false
                        if (err == null) { Toast.makeText(context, "Connected", Toast.LENGTH_SHORT).show(); onClose() } else error = err
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Live and recent requests with their progress. */
@Composable
fun DroppedNeedleRequestsDialog(vm: RequestsViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadRequests() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Your requests") },
        text = {
            val list = ui.requests
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(ui.error ?: "No requests yet")
                else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list, key = { it.mbid }) { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${r.artist} • ${dnStatusLabel(r.status)}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            r.progress?.takeIf { r.status == "downloading" }?.let {
                                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = { vm.loadRequests() }) { Text("Refresh") } },
    )
}
