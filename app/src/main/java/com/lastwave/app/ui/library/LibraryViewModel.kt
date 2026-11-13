package com.lastwave.app.ui.library

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
    PHONE("On this phone"),
}

fun tabsFor(mode: LibraryMode): List<LibraryTab> = when (mode) {
    LibraryMode.SAVED -> listOf(LibraryTab.PLAYLISTS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.PHONE)
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
    val loading: Boolean = false,
    val error: String? = null,
)

data class ArtistAlbumsState(
    val albums: List<JfItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: JellyfinRepository,
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
            LibraryTab.PHONE -> Unit
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
            LibraryTab.PHONE -> Unit
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

    private fun loadDetail(detail: LibraryDetail) {
        detailJob?.cancel()
        _detailTracks.value = TracksState(loading = detail !is LibraryDetail.Artist)
        _artistAlbums.value = ArtistAlbumsState(loading = detail is LibraryDetail.Artist)
        detailJob = viewModelScope.launch {
            try {
                when (detail) {
                    is LibraryDetail.Album ->
                        _detailTracks.value = TracksState(tracks = repository.albumTracks(detail.item.id))
                    is LibraryDetail.Playlist ->
                        _detailTracks.value = TracksState(tracks = repository.playlistTracks(detail.item.id))
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

    fun coverUrl(session: JellyfinSession, item: JfItem, size: Int = 400): String? =
        repository.coverUrl(session, item, size)

    private companion object {
        const val PAGE = 120
        const val SONG_PAGE = 150
    }
}
