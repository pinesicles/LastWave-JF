package com.lastwave.app.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.importer.ImportException
import com.lastwave.app.data.jellyfin.importer.ImportParsers
import com.lastwave.app.data.jellyfin.importer.ImportTrack
import com.lastwave.app.data.jellyfin.importer.LinkImporter
import com.lastwave.app.data.jellyfin.importer.LinkKind
import com.lastwave.app.data.jellyfin.importer.ParsedPlaylist
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports a playlist from a CSV/JSON export or a link and rebuilds it from your Jellyfin library.
 * Songs that can't be matched confidently are listed so you can pick one by hand or export them.
 */
@HiltViewModel
class JellyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val links: LinkImporter,
    private val dataStore: DataStore<Preferences>,
    private val spotify: com.lastwave.app.data.playlist.SpotifyPlaylistImporter,
    spotifyPreferences: com.lastwave.app.data.spotify.SpotifyPreferences,
) : ViewModel() {

    /** True when you're signed in to Spotify in Settings. */
    val spotifyConnected: StateFlow<Boolean> = spotifyPreferences.isConnected
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private val _spotifyPlaylists = MutableStateFlow<List<com.lastwave.app.data.playlist.SpotifyUserPlaylist>>(emptyList())
    val spotifyPlaylists: StateFlow<List<com.lastwave.app.data.playlist.SpotifyUserPlaylist>> = _spotifyPlaylists.asStateFlow()

    fun loadSpotifyPlaylists() {
        viewModelScope.launch {
            _spotifyPlaylists.value = runCatching { spotify.fetchUserPlaylists() }.getOrDefault(emptyList())
        }
    }

    fun importSpotifyPlaylist(p: com.lastwave.app.data.playlist.SpotifyUserPlaylist) = importSpotify(p.name) {
        spotify.fetchPlaylist("https://open.spotify.com/playlist/${p.id}")
    }

    fun importSpotifyLiked() = importSpotify("Liked Songs") { spotify.fetchLikedSongs() }

    private fun importSpotify(
        fallback: String,
        fetch: suspend () -> com.lastwave.app.data.playlist.ExternalPlaylistResult,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.WORKING, error = null, done = 0, total = 0) }
            try {
                val r = fetch()
                run(ParsedPlaylist(r.title.ifBlank { fallback }, r.rows.map { ImportTrack(it.title, it.artist, it.album) }), fallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Couldn't read Spotify")
            }
        }
    }

    enum class Phase { IDLE, WORKING, DONE }

    data class Unmatched(val source: ImportTrack, val suggestions: List<PlayableTrack>, val added: Boolean = false)

    data class Keys(val youtube: String = "")

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val name: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val playlistId: String? = null,
        val playlistName: String = "",
        val matched: Int = 0,
        val unmatched: List<Unmatched> = emptyList(),
        val error: String? = null,
        val keys: Keys = Keys(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private object K {
        val YOUTUBE = stringPreferencesKey("import_youtube_key")
    }

    init {
        viewModelScope.launch {
            val p = dataStore.data.first()
            _state.update {
                it.copy(keys = Keys(p[K.YOUTUBE].orEmpty()))
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun saveKeys(keys: Keys) {
        _state.update { it.copy(keys = keys) }
        viewModelScope.launch {
            dataStore.edit {
                it[K.YOUTUBE] = keys.youtube.trim()
            }
        }
    }

    fun reset() { _state.value = _state.value.copy(phase = Phase.IDLE, error = null, name = "", unmatched = emptyList(), playlistId = null) }

    private fun fail(message: String) = _state.update { it.copy(phase = Phase.IDLE, error = message) }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.WORKING, error = null, done = 0, total = 0) }
            try {
                val (text, fileName) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                    val body = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw ImportException("Couldn't read that file")
                    body to name
                }
                run(ImportParsers.parse(text), fileName?.substringBeforeLast('.') ?: "Imported playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Import failed")
            }
        }
    }

    fun importLink(link: String) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.WORKING, error = null, done = 0, total = 0) }
            try {
                val keys = _state.value.keys
                val parsed = when (links.kindOf(trimmed)) {
                    LinkKind.SPOTIFY -> spotify.fetchPlaylist(trimmed).let { r ->
                        ParsedPlaylist(r.title, r.rows.map { ImportTrack(it.title, it.artist, it.album) })
                    }
                    LinkKind.YOUTUBE -> links.youtube(trimmed, keys.youtube)
                    LinkKind.APPLE, LinkKind.TIDAL -> throw ImportException(
                        "Apple Music and Tidal don't offer a free way to read playlists. Export it to a CSV file and import that.",
                    )
                    LinkKind.UNKNOWN -> throw ImportException("Paste a Spotify or YouTube playlist link, or choose a file.")
                }
                run(parsed, "Imported playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Import failed")
            }
        }
    }

    private suspend fun run(parsed: ParsedPlaylist, fallbackName: String) {
        if (parsed.tracks.isEmpty()) throw ImportException("No songs found in that playlist")
        val name = _state.value.name.trim().ifBlank { parsed.name ?: fallbackName }
        _state.update { it.copy(phase = Phase.WORKING, total = parsed.tracks.size, done = 0, playlistName = name) }
        val gate = Semaphore(4)
        val outcomes = coroutineScope {
            parsed.tracks.map { track ->
                async {
                    gate.withPermit { repository.matchImport(track) }.also {
                        _state.update { s -> s.copy(done = s.done + 1) }
                    }
                }
            }.awaitAll()
        }
        val matched = outcomes.mapNotNull { it.match }
        val ids = matched.mapNotNull { it.jellyfinItemIdOrNull() }.distinct()
        val playlistId = repository.createPlaylist(name, ids)
        runCatching {
            ImportLeftovers.save(context, playlistId, outcomes.filter { o -> o.match == null }.map { o -> o.source })
        }
        _state.update {
            it.copy(
                phase = Phase.DONE,
                playlistId = playlistId,
                matched = matched.size,
                unmatched = outcomes.filter { o -> o.match == null }.map { o -> Unmatched(o.source, o.suggestions) },
            )
        }
    }

    suspend fun manualSearch(term: String): Result<List<PlayableTrack>> =
        if (term.isBlank()) Result.success(emptyList()) else runCatching { repository.searchSongs(term.trim(), 30) }

    fun addManual(index: Int, track: PlayableTrack) {
        val pid = _state.value.playlistId ?: return
        val id = track.jellyfinItemIdOrNull() ?: return
        viewModelScope.launch {
            try {
                repository.addToPlaylist(pid, listOf(id))
                _state.value.unmatched.getOrNull(index)?.let { u -> ImportLeftovers.remove(context, pid, u.source) }
                _state.update { s ->
                    s.copy(
                        unmatched = s.unmatched.mapIndexed { i, u -> if (i == index) u.copy(added = true) else u },
                        matched = s.matched + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't add: ${e.message}") }
            }
        }
    }

    /** Songs still unmatched, in the same CSV layout the importer reads back. */
    fun unmatchedCsv(): String {
        fun cell(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        return buildString {
            append("Track Name,Artist Name(s),Album Name,Duration (ms),ISRC\n")
            _state.value.unmatched.filter { !it.added }.forEach { u ->
                append(
                    listOf(cell(u.source.title), cell(u.source.artist), cell(u.source.album),
                        u.source.durationMs?.toString().orEmpty(), cell(u.source.isrc)).joinToString(","),
                ).append('\n')
            }
        }
    }
}
