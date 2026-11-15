package com.lastwave.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JellyfinFeedState(
    val sessionLoaded: Boolean = false,
    val session: JellyfinSession? = null,
    val recentlyAdded: List<JfItem> = emptyList(),
    val randomPicks: List<JfItem> = emptyList(),
    val shelves: List<FeedShelf> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val online: Boolean = true,
    /** Downloaded albums and playlists, offered when there's no connection. */
    val offlineItems: List<JfItem> = emptyList(),
)

/** Shelves for the Feed tab, all read from the Jellyfin server. */
@HiltViewModel
class JellyfinFeedViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val shelfBuilder: JellyFeedShelves,
    private val connectivity: com.lastwave.app.data.jellyfin.JellyConnectivity,
    private val downloads: com.lastwave.app.data.jellyfin.download.JellyDownloads,
) : ViewModel() {

    private val _state = MutableStateFlow(JellyfinFeedState())
    val state: StateFlow<JellyfinFeedState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var shelvesJob: Job? = null

    private fun addShelves(new: List<FeedShelf>) {
        if (new.isEmpty()) return
        _state.update { s ->
            val keys = new.map { it.key }.toSet()
            s.copy(shelves = (s.shelves.filter { it.key !in keys } + new).sortedBy { it.order })
        }
    }

    /** Personalised shelves load after the basics, each appearing as soon as it's ready. */
    private fun loadShelves() {
        shelvesJob?.cancel()
        shelvesJob = viewModelScope.launch {
            _state.update { it.copy(shelves = emptyList()) }
            coroutineScope {
                launch { runCatching { shelfBuilder.because() }.onSuccess(::addShelves) }
                launch {
                    val genres = runCatching { shelfBuilder.genres() }.getOrNull().orEmpty()
                    if (genres.isEmpty()) return@launch
                    launch { runCatching { shelfBuilder.timeOfDay(genres) }.getOrNull()?.let { addShelves(listOf(it)) } }
                    launch { addShelves(shelfBuilder.moods(genres)) }
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            connectivity.online.collect { on ->
                _state.update { it.copy(online = on) }
                val s = _state.value
                if (on && s.session != null && s.recentlyAdded.isEmpty() && !s.loading) refresh()
            }
        }
        viewModelScope.launch {
            downloads.collections.collect { list ->
                _state.update { s ->
                    s.copy(offlineItems = list.map { JfItem(
                        id = it.id, name = it.name, albumArtist = it.subtitle,
                        type = if (it.type == com.lastwave.app.data.jellyfin.download.COLLECTION_PLAYLIST) "Playlist" else "MusicAlbum",
                    ) })
                }
            }
        }
        viewModelScope.launch {
            repository.session.distinctUntilChanged().collect { session ->
                _state.update { it.copy(sessionLoaded = true, session = session) }
                if (session != null) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                coroutineScope {
                    val recent = async { repository.recentlyAddedAlbums(20).items }
                    val random = async { repository.randomAlbums(20).items }
                    val r = recent.await()
                    val q = random.await()
                    _state.update { it.copy(recentlyAdded = r, randomPicks = q, loading = false) }
                    loadShelves()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your feed") }
            }
        }
    }

    suspend fun likedTracks(): List<PlayableTrack> = repository.favoriteTracks()

    suspend fun randomTracks(): List<PlayableTrack> = repository.randomTracks(100)

    fun coverUrl(session: JellyfinSession, item: JfItem, size: Int = 400): String? =
        repository.coverUrl(session, item, size)
}
