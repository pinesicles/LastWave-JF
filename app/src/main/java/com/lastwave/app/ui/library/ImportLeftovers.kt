package com.lastwave.app.ui.library

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** Songs an import couldn't match, kept per created playlist so they can be retried or requested later. */
object ImportLeftovers {
    private fun prefs(c: Context) = c.getSharedPreferences("jw_import_left", Context.MODE_PRIVATE)

    fun load(c: Context, playlistId: String): List<ImportTrack> = runCatching {
        val a = JSONArray(prefs(c).getString(playlistId, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ImportTrack(o.optString("t"), o.optString("a"), o.optString("al").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    fun save(c: Context, playlistId: String, tracks: List<ImportTrack>) {
        if (tracks.isEmpty()) { prefs(c).edit().remove(playlistId).apply(); return }
        val a = JSONArray()
        tracks.forEach { a.put(JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album ?: "")) }
        prefs(c).edit().putString(playlistId, a.toString()).apply()
    }

    fun remove(c: Context, playlistId: String, t: ImportTrack) =
        save(c, playlistId, load(c, playlistId).filterNot { it.title == t.title && it.artist == t.artist })

    fun count(c: Context, playlistId: String) = load(c, playlistId).size
}

@HiltViewModel
class ImportLeftoversViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jelly: JellyfinRepository,
) : ViewModel() {
    private val _left = MutableStateFlow<List<ImportTrack>>(emptyList())
    val left: StateFlow<List<ImportTrack>> = _left
    val searching = MutableStateFlow(false)
    val note = MutableStateFlow<String?>(null)
    private var pid = ""

    fun load(playlistId: String) { pid = playlistId; _left.value = ImportLeftovers.load(context, playlistId) }

    /** Looks each leftover up in the library again (title first, then artist) and adds the hits. */
    fun searchAgain() {
        if (searching.value) return
        viewModelScope.launch {
            searching.value = true
            var found = 0
            for (t in _left.value) {
                val hit = runCatching { jelly.findSong(t.title, t.artist) }.getOrNull() ?: continue
                val id = hit.jellyfinItemIdOrNull() ?: continue
                runCatching { jelly.addToPlaylist(pid, listOf(id)) }.onSuccess {
                    ImportLeftovers.remove(context, pid, t); found++
                }
            }
            _left.value = ImportLeftovers.load(context, pid)
            note.value = if (found > 0) "Added $found to the playlist" else "Nothing new found in your library"
            searching.value = false
        }
    }

    fun drop(playlistId: String) { ImportLeftovers.save(context, playlistId, emptyList()); _left.value = emptyList() }
}

/** Playlist-menu dialog listing songs the import couldn't find. */
@Composable
internal fun ImportLeftoversDialog(playlistId: String, onClose: () -> Unit) {
    val vm: ImportLeftoversViewModel = hiltViewModel(key = "import-left")
    androidx.compose.runtime.LaunchedEffect(playlistId) { vm.load(playlistId) }
    val left by vm.left.collectAsStateWithLifecycle()
    val busy by vm.searching.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    var requesting by remember { mutableStateOf(false) }
    if (requesting) {
        RequestMissingDialog(
            left.map { JellyImportViewModel.Unmatched(it, emptyList()) },
            onClose = { requesting = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Unmatched songs (${left.size})") },
        text = {
            Column {
                note?.let { Text(it) }
                LazyColumn {
                    items(left) { t -> Text("${t.title} — ${t.artist}", maxLines = 1) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.searchAgain() }, enabled = !busy && left.isNotEmpty()) {
                Text(if (busy) "Searching…" else "Search library again")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { requesting = true }, enabled = left.isNotEmpty()) { Text("Request") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
