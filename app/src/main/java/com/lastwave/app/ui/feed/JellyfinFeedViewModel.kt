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
    val loading: Boolean = false,
    val error: String? = null,
)

/** Shelves for the Feed tab, all read from the Jellyfin server. */
@HiltViewModel
class JellyfinFeedViewModel @Inject constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(JellyfinFeedState())
    val state: StateFlow<JellyfinFeedState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
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
