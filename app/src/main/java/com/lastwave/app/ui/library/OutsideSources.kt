package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OutsideState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val results: List<PlayableTrack> = emptyList(),
    val artists: List<com.lastwave.app.data.search.SearchResultItem> = emptyList(),
    val albums: List<com.lastwave.app.data.search.SearchResultItem> = emptyList(),
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
    private val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator,
    private val jellyNavigator: JellyNavigator,
    private val jelly: com.lastwave.app.data.jellyfin.JellyfinRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    /** Looks for a YouTube song, album or artist on your Jellyfin server; opens its page when found. */
    internal fun findOnServer(q: FindQuery, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val found = try {
                when (q.kind) {
                    FindKind.ARTIST -> jellyNavigator.tryArtist(q.artist.ifBlank { q.title })
                    FindKind.ALBUM -> jellyNavigator.tryAlbum(q.title, q.artist)
                    FindKind.SONG -> {
                        val hit = jelly.findSong(q.title, q.artist)
                        if (hit == null) false else {
                            val album = hit.album?.takeIf { it.isNotBlank() }
                            if (album == null || !jellyNavigator.tryAlbum(album, hit.artist)) jellyNavigator.tryArtist(hit.artist)
                            true
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            onResult(found)
        }
    }

    /** Opens LastWave's YouTube Music artist page even if the artist is also in your Jellyfin library. */
    fun openArtistOnYouTube(name: String, browseId: String? = null) = navigator.openArtist(name, browseId, youtubeOnly = true)

    fun openAlbumOnYouTube(title: String, artist: String, browseId: String? = null) =
        navigator.openAlbum(title, artist, browseId, youtubeOnly = true)

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
                val (found, artists, albums) = kotlinx.coroutines.coroutineScope {
                    val t = async { searchRepository.search(SearchTab.TRACKS, query) }
                    val ar = async { runCatching { searchRepository.search(SearchTab.ARTISTS, query) }.getOrDefault(emptyList()) }
                    val al = async { runCatching { searchRepository.search(SearchTab.ALBUMS, query) }.getOrDefault(emptyList()) }
                    Triple(t.await().map { it.toPlayableTrack() }, ar.await(), al.await())
                }
                _state.update { it.copy(loading = false, results = found.take(15), artists = artists.take(10), albums = albums.take(10)) }
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
        if (recsRequested || com.lastwave.app.data.network.DataMeter.savingNow()) return
        recsRequested = true
        viewModelScope.launch {
            try {
                // Your Jellyfin favourites seed the shelf, and songs you already like are left out of it.
                val favs = try {
                    jelly.favoriteTracks()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val norm = { s: String -> com.lastwave.app.data.jellyfin.importer.ImportMatcher.norm(s) }
                val have = favs.mapTo(HashSet()) { norm(it.title) + "|" + norm(it.artist) }
                val seeds = favs.shuffled().take(30).map { GeneratedTrack(name = it.title, artist = it.artist, album = it.album) }
                // The shelf is rebuilt at most every 12 hours; in between it comes from a small on-disk copy (no YouTube traffic).
                val fresh = RecsCache.load(appContext) ?: generate.fetchRecommendations(20, extraSeeds = seeds.take(8))
                    .also { RecsCache.save(appContext, it) }
                _recommendations.value = fresh
                    .map { it.toPlayableTrack() }
                    .filter { !it.videoId.isNullOrBlank() && (norm(it.title) + "|" + norm(it.artist)) !in have }
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
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)) {
            Text("Outside your library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "YouTube, and your request server for adding music",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "request_on_server") {
        val request = com.lastwave.app.ui.requests.LocalRequestAlbum.current
        OutsideActionCard(Icons.Filled.CloudDownload, "Request on your server", "Find “$query” to add to your library") { request(query) }
    }
    when {
        !state.expanded -> item(key = "outside_show") {
            OutsideActionCard(Icons.Filled.PlayCircle, "Search YouTube", "Songs, artists and albums for “$query”") { viewModel.search(query) }
        }
        state.loading -> item(key = "outside_loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.results.isEmpty() && state.artists.isEmpty() && state.albums.isEmpty() -> item(key = "outside_empty") {
            Text(
                if (state.failed) "Couldn't reach YouTube" else "Nothing found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        else -> {
            if (state.artists.isNotEmpty()) item(key = "o_artists") {
                OutsideEntityShelf("Artists", state.artists, round = true, viewModel = viewModel)
            }
            if (state.albums.isNotEmpty()) item(key = "o_albums") {
                OutsideEntityShelf("Albums", state.albums, round = false, viewModel = viewModel)
            }
            if (state.results.isNotEmpty()) item(key = "o_songs_h") {
                Text(
                    "Songs",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
                )
            }
            itemsIndexed(state.results, key = { i, t -> "o_${i}_${t.videoId}" }) { index, track ->
                val request = com.lastwave.app.ui.requests.LocalRequestAlbum.current
                val find = LocalFindOnServer.current
                JellyTrackRow(
                    track,
                    groupPositionFor(index, state.results.size),
                    actions = buildList {
                        add(RowAction("Add to playlist", Icons.Filled.PlaylistAdd) { addToLocalPlaylist(track) })
                        add(RowAction("Find on server", Icons.Filled.Search) { find(FindQuery(FindKind.SONG, track.title, track.artist, track.album)) })
                        add(RowAction("See artist on YouTube", Icons.Filled.Person) { viewModel.openArtistOnYouTube(track.artist) })
                        track.album?.takeIf { it.isNotBlank() }?.let { album ->
                            add(RowAction("See album on YouTube", Icons.Filled.Album) { viewModel.openAlbumOnYouTube(album, track.artist) })
                        }
                        add(
                            RowAction("Request album on server", Icons.Filled.CloudDownload) {
                                request(listOf(track.artist, track.album?.takeIf { it.isNotBlank() } ?: track.title).filter { it.isNotBlank() }.joinToString(" "))
                            },
                        )
                        add(RowAction("More by ${track.artist} on server", Icons.Filled.CloudDownload) { request(track.artist) })
                    },
                    trailing = { SourceBadge() },
                ) { player.playQueue(state.results, index, "YouTube search", startShuffled = false) }
            }
        }
    }
}

/**
 * A row of YouTube artists or albums. Tap to open its YouTube page; hold for the menu (see it on YouTube,
 * request it or the artist's other albums on your server).
 */
@Composable
private fun OutsideEntityShelf(
    title: String,
    items: List<com.lastwave.app.data.search.SearchResultItem>,
    round: Boolean,
    viewModel: OutsideSourcesViewModel,
) {
    val request = com.lastwave.app.ui.requests.LocalRequestAlbum.current
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.lastwave.app.data.search.SearchResultItem?>(null) }
    fun open(a: com.lastwave.app.data.search.SearchResultItem) {
        if (round) viewModel.openArtistOnYouTube(a.name, a.entityId) else viewModel.openAlbumOnYouTube(a.name, a.artist.orEmpty(), a.entityId)
    }
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { i, a -> "${title}_${i}_${a.entityId ?: a.name}" }) { _, a ->
                CoverCard(
                    title = a.name, subtitle = if (round) "Artist" else a.artist, coverUrl = a.artworkUrl, round = round,
                    onClick = { open(a) },
                    onLongClick = { menuFor = a },
                    modifier = Modifier.width(130.dp),
                )
            }
        }
    }
    menuFor?.let { a ->
        val artist = if (round) a.name else a.artist.orEmpty()
        val find = LocalFindOnServer.current
        OutsideActionSheet(
            title = a.name,
            subtitle = if (round) "Artist" else a.artist,
            coverUrl = a.artworkUrl,
            round = round,
            actions = buildList {
                add(
                    SheetAction(Icons.Filled.Search, "Find on server", "Check if it's already in your library") {
                        find(FindQuery(if (round) FindKind.ARTIST else FindKind.ALBUM, a.name, artist))
                    },
                )
                if (round) {
                    add(SheetAction(Icons.Filled.Person, "See artist on YouTube", "Songs, albums and more") { open(a) })
                    add(SheetAction(Icons.Filled.CloudDownload, "Albums to request", "Browse ${a.name}'s albums on your server") { request(a.name) })
                } else {
                    add(SheetAction(Icons.Filled.Album, "See album on YouTube", "Open it and play the songs") { open(a) })
                    add(SheetAction(Icons.Filled.CloudDownload, "Request album on server", "Adds the whole album to your library") { request("$artist ${a.name}".trim()) })
                    if (artist.isNotBlank()) {
                        add(SheetAction(Icons.Filled.Person, "See $artist on YouTube", null) { viewModel.openArtistOnYouTube(artist) })
                        add(SheetAction(Icons.Filled.CloudDownload, "More by $artist on server", "Browse their other albums to request") { request(artist) })
                    }
                }
            },
            onDismiss = { menuFor = null },
        )
    }
}

/** "From YouTube" shelf for the Feed: recommended songs that aren't necessarily in your library. */
@Composable
internal fun YouTubeShelf(
    tracks: List<PlayableTrack>,
    player: MusicPlayer,
    viewModel: OutsideSourcesViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    if (tracks.isEmpty()) return
    val request = com.lastwave.app.ui.requests.LocalRequestAlbum.current
    val find = LocalFindOnServer.current
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    menuFor?.let { index ->
        val t = tracks.getOrNull(index)
        if (t == null) { menuFor = null; return@let }
        val album = t.album?.takeIf { it.isNotBlank() }
        OutsideActionSheet(
            title = t.title,
            subtitle = listOfNotNull(t.artist.ifBlank { null }, album).joinToString(" • "),
            coverUrl = t.artworkUrl,
            round = false,
            actions = buildList {
                add(SheetAction(Icons.Filled.PlayArrow, "Play", null) { player.playQueue(tracks, index, "From YouTube", startShuffled = false) })
                add(
                    SheetAction(Icons.Filled.Search, "Find on server", "Check if it's already in your library") {
                        find(FindQuery(FindKind.SONG, t.title, t.artist, album))
                    },
                )
                if (album != null) add(SheetAction(Icons.Filled.Album, "See album on YouTube", album) { viewModel.openAlbumOnYouTube(album, t.artist) })
                add(SheetAction(Icons.Filled.Person, "See ${t.artist} on YouTube", null) { viewModel.openArtistOnYouTube(t.artist) })
                add(
                    SheetAction(Icons.Filled.CloudDownload, "Request album on server", "Adds the whole album to your library") {
                        request(listOf(t.artist, album ?: t.title).filter { it.isNotBlank() }.joinToString(" "))
                    },
                )
                add(SheetAction(Icons.Filled.CloudDownload, "More by ${t.artist} on server", "Browse their other albums to request") { request(t.artist) })
            },
            onDismiss = { menuFor = null },
        )
    }
    Column {
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
                    onLongClick = { menuFor = index },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}
