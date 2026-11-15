package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.search.toPlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OutsideState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val results: List<PlayableTrack> = emptyList(),
)

/**
 * Music that isn't on your Jellyfin server, from LastWave's YouTube side. Always kept apart from your
 * library: its own search section and its own Feed shelf, with a source badge. Songs here play
 * straight away and can go in local (phone) playlists. Jellyfin playlists only hold server songs.
 */
@HiltViewModel
class OutsideSourcesViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val generate: GenerateRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OutsideState())
    val state: StateFlow<OutsideState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** The query changed: collapse so nothing is fetched until the person asks for it. */
    fun reset() {
        searchJob?.cancel()
        _state.value = OutsideState()
    }

    fun search(query: String) {
        searchJob?.cancel()
        _state.value = OutsideState(expanded = true, loading = true)
        searchJob = viewModelScope.launch {
            try {
                val found = searchRepository.search(SearchTab.TRACKS, query).map { it.toPlayableTrack() }
                _state.update { it.copy(loading = false, results = found.take(15)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private val _recommendations = MutableStateFlow<List<PlayableTrack>>(emptyList())
    val recommendations: StateFlow<List<PlayableTrack>> = _recommendations.asStateFlow()
    private var recsRequested = false

    /** LastWave's "My Recommendations", fetched once per app session and only while online. */
    fun loadRecommendations() {
        if (recsRequested) return
        recsRequested = true
        viewModelScope.launch {
            try {
                _recommendations.value = generate.fetchRecommendations(20)
                    .map { it.toPlayableTrack() }
                    .filter { !it.videoId.isNullOrBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recsRequested = false
            }
        }
    }
}

@Composable
internal fun SourceBadge(label: String = "YouTube") {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "Outside your library" section at the end of the Feed's search results. */
internal fun LazyListScope.outsideSearchItems(
    query: String,
    state: OutsideState,
    viewModel: OutsideSourcesViewModel,
    player: MusicPlayer,
    addToLocalPlaylist: (PlayableTrack) -> Unit,
) {
    if (query.isBlank()) return
    item(key = "h_outside") {
        Text(
            "Outside your library",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 2.dp),
        )
    }
    when {
        !state.expanded -> item(key = "outside_show") {
            TextButton(onClick = { viewModel.search(query) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Search YouTube for “$query”")
            }
        }
        state.loading -> item(key = "outside_loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.results.isEmpty() -> item(key = "outside_empty") {
            Text(
                if (state.failed) "Couldn't reach YouTube" else "Nothing found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        else -> itemsIndexed(state.results, key = { i, t -> "o_${i}_${t.videoId}" }) { index, track ->
            JellyTrackRow(
                track,
                groupPositionFor(index, state.results.size),
                actions = listOf(RowAction("Add to playlist") { addToLocalPlaylist(track) }),
                trailing = { SourceBadge() },
            ) { player.playQueue(state.results, index, "YouTube search", startShuffled = false) }
        }
    }
}

/** "From YouTube" shelf for the Feed: recommended songs that aren't necessarily in your library. */
@Composable
internal fun YouTubeShelf(tracks: List<PlayableTrack>, player: MusicPlayer) {
    if (tracks.isEmpty()) return
    androidx.compose.foundation.layout.Column {
        Text(
            "From YouTube",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(tracks, key = { i, t -> "yt_${i}_${t.videoId}" }) { index, track ->
                CoverCard(
                    title = track.title,
                    subtitle = track.artist,
                    coverUrl = track.artworkUrl,
                    round = false,
                    onClick = { player.playQueue(tracks, index, "From YouTube", startShuffled = false) },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}
