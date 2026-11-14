package com.lastwave.app.ui.library

import com.lastwave.app.data.jellyfin.download.JfCollectionEntity
import com.lastwave.app.data.jellyfin.download.JellyDownloads
import com.lastwave.app.data.jellyfin.download.CollectionProgress
import com.lastwave.app.data.jellyfin.JellyfinException
import kotlinx.coroutines.withTimeoutOrNull
import com.lastwave.app.data.jellyfin.SearchHits
import kotlinx.coroutines.delay
import com.lastwave.app.data.jellyfin.jellyfinItemIdOrNull
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.data.jellyfin.JfPage
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SAVED  = "my" library: playlists (with Liked songs pinned), saved albums, saved artists.
 * SERVER = everything on the Jellyfin server: all albums, artists and songs.
 */
enum class LibraryMode { SAVED, SERVER }

enum class LibraryTab(val label: String) {
    PLAYLISTS("Playlists"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    DOWNLOADED("Downloaded"),
    PHONE("On this phone"),
}

fun tabsFor(mode: LibraryMode): List<LibraryTab> = when (mode) {
    LibraryMode.SAVED -> listOf(LibraryTab.PLAYLISTS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.DOWNLOADED, LibraryTab.PHONE)
    LibraryMode.SERVER -> listOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.SONGS)
}

sealed interface LibrarySession {
    data object Loading : LibrarySession
    data object SignedOut : LibrarySession
    data class SignedIn(val session: JellyfinSession) : LibrarySession
}

/** Stand-in item for the pinned "Liked songs" playlist. */
val LikedSongsItem = JfItem(id = "liked-songs", name = "Liked songs")

/** One screen pushed on top of the library lists. */
sealed interface LibraryDetail {
    val item: JfItem

    data class Album(override val item: JfItem) : LibraryDetail
    data class Artist(override val item: JfItem) : LibraryDetail
    data class Playlist(override val item: JfItem) : LibraryDetail
    data class LikedSongs(override val item: JfItem = LikedSongsItem) : LibraryDetail
}

data class ListState(
    val items: List<JfItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = items.size < total
}

data class SongsState(
    val tracks: List<PlayableTrack> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = tracks.size < total
}

data class TracksState(
    val tracks: List<PlayableTrack> = emptyList(),
    /** Playlist pages only: entry id for each track, same order as [tracks]. */
    val entryIds: List<String?> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ArtistAlbumsState(
    val albums: List<JfItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class SearchState(
    val query: String = "",
    val hits: SearchHits = SearchHits(),
    val loading: Boolean = false,
    val error: String? = null,
)

private fun SearchHits.matching(q: String): SearchHits {
    fun JfItem.hit() = name.orEmpty().contains(q, true) || albumArtist.orEmpty().contains(q, true)
    return SearchHits(
        albums = albums.filter { it.hit() },
        artists = artists.filter { it.hit() },
        playlists = playlists.filter { it.hit() },
        tracks = tracks.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || (it.album ?: "").contains(q, true)
        },
    )
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val downloads: JellyDownloads,
) : ViewModel() {

    val sessionState: StateFlow<LibrarySession> = repository.session
        .map { if (it == null) LibrarySession.SignedOut else LibrarySession.SignedIn(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySession.Loading)

    // ---- mode and tabs -------------------------------------------------------------

    private val _mode = MutableStateFlow<LibraryMode?>(null)
    val mode: StateFlow<LibraryMode?> = _mode.asStateFlow()

    val tabs: StateFlow<List<LibraryTab>> = _mode
        .map { it?.let(::tabsFor).orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _tab = MutableStateFlow(LibraryTab.PLAYLISTS)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    /** First call wins; later calls (recomposition) are ignored. Playlists is the saved library's home. */
    fun start(mode: LibraryMode) {
        if (_mode.value != null) return
        _mode.value = mode
        _tab.value = tabsFor(mode).first()
    }

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        ensureLoaded()
    }

    // Locally known favourite flips (list items are immutable snapshots).
    private val _favouriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favouriteOverrides: StateFlow<Map<String, Boolean>> = _favouriteOverrides.asStateFlow()

    // ---- paged lists -----------------------------------------------------------------

    private val savedOnly: Boolean get() = _mode.value == LibraryMode.SAVED

    private inner class Paged(private val fetch: suspend (start: Int) -> JfPage) {
        val state = MutableStateFlow(ListState())
        private var job: Job? = null

        fun reset() {
            job?.cancel()
            state.value = ListState()
            loadMore()
        }

        fun loadMore() {
            val current = state.value
            if (current.loading || (current.loaded && !current.hasMore)) return
            state.update { it.copy(loading = true, error = null) }
            job = viewModelScope.launch {
                try {
                    val page = fetch(current.items.size)
                    state.update {
                        it.copy(
                            items = it.items + page.items,
                            total = page.total,
                            loading = false,
                            loaded = true,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.update { it.copy(loading = false, loaded = true, error = e.message ?: "Couldn't load") }
                }
            }
        }
    }

    private val albumsPaged = Paged { start -> repository.albums(start, PAGE, savedOnly) }
    private val artistsPaged = Paged { start -> repository.artists(start, PAGE, savedOnly) }
    private val playlistsPaged = Paged { repository.playlists() }

    val albums: StateFlow<ListState> = albumsPaged.state.asStateFlow()
    val artists: StateFlow<ListState> = artistsPaged.state.asStateFlow()
    val playlists: StateFlow<ListState> = playlistsPaged.state.asStateFlow()

    private val _songs = MutableStateFlow(SongsState())
    val songs: StateFlow<SongsState> = _songs.asStateFlow()
    private var songsJob: Job? = null

    fun loadMoreAlbums() = albumsPaged.loadMore()
    fun loadMoreArtists() = artistsPaged.loadMore()

    fun loadMoreSongs() {
        val current = _songs.value
        if (current.loading || (current.loaded && !current.hasMore)) return
        _songs.update { it.copy(loading = true, error = null) }
        songsJob = viewModelScope.launch {
            try {
                val (page, total) = repository.songsPage(current.tracks.size, SONG_PAGE)
                _songs.update { it.copy(tracks = it.tracks + page, total = total, loading = false, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _songs.update { it.copy(loading = false, loaded = true, error = e.message ?: "Couldn't load") }
            }
        }
    }

    /** Loads the visible tab's first page once Jellyfin is signed in. Safe to call repeatedly. */
    fun ensureLoaded() {
        if (sessionState.value !is LibrarySession.SignedIn) return
        when (_tab.value) {
            LibraryTab.PLAYLISTS -> if (!playlistsPaged.state.value.loaded) playlistsPaged.loadMore()
            LibraryTab.ALBUMS -> if (!albumsPaged.state.value.loaded) albumsPaged.loadMore()
            LibraryTab.ARTISTS -> if (!artistsPaged.state.value.loaded) artistsPaged.loadMore()
            LibraryTab.SONGS -> if (!_songs.value.loaded) loadMoreSongs()
            LibraryTab.DOWNLOADED, LibraryTab.PHONE -> Unit
        }
    }

    fun refresh() {
        when (_tab.value) {
            LibraryTab.PLAYLISTS -> playlistsPaged.reset()
            LibraryTab.ALBUMS -> albumsPaged.reset()
            LibraryTab.ARTISTS -> artistsPaged.reset()
            LibraryTab.SONGS -> {
                songsJob?.cancel()
                _songs.value = SongsState()
                loadMoreSongs()
            }
            LibraryTab.DOWNLOADED, LibraryTab.PHONE -> Unit
        }
    }

    // ---- detail screens ----------------------------------------------------------------

    private val _detailStack = MutableStateFlow<List<LibraryDetail>>(emptyList())
    val currentDetail: StateFlow<LibraryDetail?> = _detailStack
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _detailTracks = MutableStateFlow(TracksState())
    val detailTracks: StateFlow<TracksState> = _detailTracks.asStateFlow()

    private val _artistAlbums = MutableStateFlow(ArtistAlbumsState())
    val artistAlbums: StateFlow<ArtistAlbumsState> = _artistAlbums.asStateFlow()

    private var detailJob: Job? = null

    fun open(detail: LibraryDetail) {
        _detailStack.update { it + detail }
        loadDetail(detail)
    }

    /** Reloads the page on top of the stack (the "Try again" button). */
    fun reloadDetail() {
        _detailStack.value.lastOrNull()?.let(::loadDetail)
    }

    /** Returns false when there was nothing to go back to. */
    fun back(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        val next = stack.dropLast(1)
        _detailStack.value = next
        next.lastOrNull()?.let(::loadDetail)
        return true
    }

    /** A fully downloaded album opens instantly from the phone; otherwise ask the server. */
    private suspend fun albumTracks(id: String): List<PlayableTrack> {
        val (local, complete) = repository.localCollection(id)
        if (complete) return local
        return try {
            repository.albumTracks(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (local.isNotEmpty()) local else throw e
        }
    }

    private fun loadDetail(detail: LibraryDetail) {
        detailJob?.cancel()
        _detailTracks.value = TracksState(loading = detail !is LibraryDetail.Artist)
        _artistAlbums.value = ArtistAlbumsState(loading = detail is LibraryDetail.Artist)
        detailJob = viewModelScope.launch {
            try {
                when (detail) {
                    is LibraryDetail.Album ->
                        _detailTracks.value = TracksState(tracks = albumTracks(detail.item.id))
                    is LibraryDetail.Playlist -> {
                        val id = detail.item.id
                        val entries = try {
                            withTimeoutOrNull(8_000) { repository.playlistEntries(id) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        if (entries != null) {
                            _detailTracks.value = TracksState(
                                tracks = entries.map { it.track },
                                entryIds = entries.map { it.entryId },
                            )
                            // A playlist kept in sync picks up changes made elsewhere.
                            launch { runCatching { downloads.sync(id) } }
                        } else {
                            // Server slow or unreachable: show the downloaded copy (not editable).
                            val (local, _) = repository.localCollection(id)
                            if (local.isEmpty()) throw JellyfinException("Couldn't reach the server")
                            _detailTracks.value = TracksState(tracks = local, entryIds = local.map { null })
                        }
                    }
                    is LibraryDetail.LikedSongs ->
                        _detailTracks.value = TracksState(tracks = repository.favoriteTracks())
                    is LibraryDetail.Artist ->
                        _artistAlbums.value = ArtistAlbumsState(albums = repository.artistAlbums(detail.item.id).items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Couldn't load"
                if (detail is LibraryDetail.Artist) {
                    _artistAlbums.value = ArtistAlbumsState(error = message)
                } else {
                    _detailTracks.value = TracksState(error = message)
                }
            }
        }
    }

    // ---- search ------------------------------------------------------------------------

    private val _search = MutableStateFlow(SearchState())
    /** Server mode searches the whole server; saved mode filters your saved items. */
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null
    private var savedCache: SearchHits? = null

    fun setSearchQuery(query: String) {
        _search.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchState()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _search.update { it.copy(loading = true, error = null) }
            try {
                val hits = if (_mode.value == LibraryMode.SAVED) {
                    (savedCache ?: repository.savedHits().also { savedCache = it }).matching(query.trim())
                } else {
                    repository.searchHits(query.trim())
                }
                _search.update { it.copy(hits = hits, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.update { it.copy(loading = false, error = e.message ?: "Search failed") }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        savedCache = null
        _search.value = SearchState()
    }

    // ---- playlist editing ----------------------------------------------------------------

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-line results ("Added 3 songs", errors) for the screen to show. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private fun playlistId(): String? = (_detailStack.value.lastOrNull() as? LibraryDetail.Playlist)?.item?.id

    private fun launchEdit(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit("$failure: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Call when a playlist picker opens so it shows the server's current playlists. */
    fun refreshPlaylists() = playlistsPaged.reset()

    fun createPlaylist(name: String, tracks: List<PlayableTrack> = emptyList()) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchEdit("Couldn't create playlist") {
            repository.createPlaylist(clean, tracks.mapNotNull { it.jellyfinItemIdOrNull() })
            playlistsPaged.reset()
            _events.tryEmit(if (tracks.isEmpty()) "Created “$clean”" else "Created “$clean” with ${tracks.size} songs")
        }
    }

    fun addToPlaylist(playlist: JfItem, tracks: List<PlayableTrack>) {
        val ids = tracks.mapNotNull { it.jellyfinItemIdOrNull() }
        if (ids.isEmpty()) return
        launchEdit("Couldn't add to playlist") {
            repository.addToPlaylist(playlist.id, ids)
            runCatching { downloads.sync(playlist.id) }
            playlistsPaged.reset()
            _events.tryEmit("Added ${ids.size} to “${playlist.name.orEmpty()}”")
        }
    }

    fun removeFromPlaylist(index: Int) {
        val id = playlistId() ?: return
        val state = _detailTracks.value
        val entry = state.entryIds.getOrNull(index) ?: return
        launchEdit("Couldn't remove song") {
            repository.removeFromPlaylist(id, entry)
            runCatching { downloads.sync(id) }
            _detailTracks.update {
                it.copy(
                    tracks = it.tracks.filterIndexed { i, _ -> i != index },
                    entryIds = it.entryIds.filterIndexed { i, _ -> i != index },
                )
            }
            playlistsPaged.reset()
        }
    }

    /** Moves one song up (-1) or down (+1). The list changes at once; a failure reloads it. */
    fun moveInPlaylist(index: Int, delta: Int) {
        val id = playlistId() ?: return
        val state = _detailTracks.value
        val target = index + delta
        val entry = state.entryIds.getOrNull(index) ?: return
        if (target !in state.tracks.indices) return
        val tracks = state.tracks.toMutableList().apply { add(target, removeAt(index)) }
        val entries = state.entryIds.toMutableList().apply { add(target, removeAt(index)) }
        _detailTracks.value = state.copy(tracks = tracks, entryIds = entries)
        viewModelScope.launch {
            try {
                repository.movePlaylistItem(id, entry, target)
                runCatching { downloads.sync(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit("Couldn't move song: ${e.message ?: "unknown error"}")
                reloadDetail()
            }
        }
    }

    fun renameCurrentPlaylist(name: String) {
        val id = playlistId() ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchEdit("Couldn't rename playlist") {
            repository.renamePlaylist(id, clean)
            _detailStack.update { stack ->
                stack.map { if (it is LibraryDetail.Playlist && it.item.id == id) LibraryDetail.Playlist(it.item.copy(name = clean)) else it }
            }
            playlistsPaged.reset()
        }
    }

    fun deleteCurrentPlaylist() {
        val id = playlistId() ?: return
        launchEdit("Couldn't delete playlist") {
            repository.deletePlaylist(id)
            runCatching { downloads.remove(id) }
            back()
            playlistsPaged.reset()
            _events.tryEmit("Playlist deleted")
        }
    }

    /** Un-hearts a song from the Liked songs page. */
    fun removeFromLiked(index: Int) {
        val track = _detailTracks.value.tracks.getOrNull(index) ?: return
        val id = track.jellyfinItemIdOrNull() ?: return
        launchEdit("Couldn't remove song") {
            repository.setFavorite(id, false)
            _detailTracks.update {
                it.copy(tracks = it.tracks.filterIndexed { i, _ -> i != index })
            }
        }
    }

    // ---- downloads and covers -------------------------------------------------------------

    val downloadedCollections: StateFlow<List<JfCollectionEntity>> =
        downloads.collections.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloadProgress: StateFlow<Map<String, CollectionProgress>> =
        downloads.progress.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _coverTags = MutableStateFlow<Map<String, String>>(emptyMap())
    val coverTags: StateFlow<Map<String, String>> = _coverTags.asStateFlow()

    /** Saves an album/playlist for offline use. Downloading also hearts it, so it shows up as saved. */
    fun downloadCollection(item: JfItem, type: String) {
        launchEdit("Couldn't start download") {
            downloads.download(item, type)
            if (!(_favouriteOverrides.value[item.id] ?: item.isFavorite)) {
                repository.setFavorite(item.id, true)
                _favouriteOverrides.update { it + (item.id to true) }
            }
            _events.tryEmit("Downloading “${item.name.orEmpty()}”")
        }
    }

    fun removeDownload(id: String) {
        launchEdit("Couldn't remove download") {
            downloads.remove(id)
            _events.tryEmit("Download removed")
        }
    }

    fun setKeepInSync(id: String, on: Boolean) {
        launchEdit("Couldn't change sync") { downloads.setAutoSync(id, on) }
    }

    fun changeCover(bytes: ByteArray, mime: String) {
        val id = playlistId() ?: return
        launchEdit("Couldn't change cover") {
            repository.setItemImage(id, bytes, mime)
            _coverTags.update { it + (id to "v${System.currentTimeMillis()}") }
            playlistsPaged.reset()
            _events.tryEmit("Cover updated")
        }
    }

    // ---- favourites ----------------------------------------------------------------------

    fun toggleFavourite(item: JfItem) {
        val current = _favouriteOverrides.value[item.id] ?: item.isFavorite
        val target = !current
        // Optimistic: flip now, undo if the server refuses.
        _favouriteOverrides.update { it + (item.id to target) }
        viewModelScope.launch {
            try {
                repository.setFavorite(item.id, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _favouriteOverrides.update { it + (item.id to !target) }
            }
        }
    }

    /** A freshly uploaded cover, then the copy on the phone (works offline), then the server's. */
    fun coverUrl(session: JellyfinSession, item: JfItem, size: Int = 400): String? {
        _coverTags.value[item.id]?.let { return repository.coverUrlForTag(session, item.id, it, size) }
        repository.localCover(item.id)?.let { return it }
        return repository.coverUrl(session, item, size)
    }

    private companion object {
        const val PAGE = 120
        const val SONG_PAGE = 150
    }
}
