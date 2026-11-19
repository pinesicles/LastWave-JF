package com.lastwave.app.ui.feed

import com.lastwave.app.ui.library.LocalTopInset
import com.lastwave.app.ui.library.LibrarySession
import com.lastwave.app.ui.library.JellySearchResults
import com.lastwave.app.ui.library.JellySearchField
import com.lastwave.app.ui.library.JellyChipsBar
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.GlassHeaderLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.library.JellyHeaderActions
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.R
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.ui.library.CenteredProgress
import com.lastwave.app.ui.library.CoverCard
import com.lastwave.app.ui.library.ErrorWithRetry
import com.lastwave.app.ui.library.LibraryDetail
import com.lastwave.app.ui.library.LibraryMode
import com.lastwave.app.ui.library.LibraryScreen
import com.lastwave.app.ui.library.LibraryViewModel
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape
import kotlinx.coroutines.launch

private val SECTIONS = listOf("Feed", "Library")

/**
 * The Feed tab. Two chips replace the old mood chips:
 *  - Feed: shelves built from your Jellyfin server.
 *  - Library: everything on the server (all albums, artists, songs).
 * Opening an album from either one shows its page here.
 */
@Composable
fun JellyfinFeedHome(
    active: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    feedViewModel: JellyfinFeedViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(key = "library-${LibraryMode.SERVER.name}"),
) {
    remember(libraryViewModel) { libraryViewModel.start(LibraryMode.SERVER); true }

    val detail by libraryViewModel.currentDetail.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }

    // An album or artist page takes over the whole tab until you go back.
    if (detail != null) {
        LibraryScreen(
            mode = LibraryMode.SERVER,
            embedded = false,
            active = active,
            viewModel = libraryViewModel,
        )
        return
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    val search by libraryViewModel.search.collectAsStateWithLifecycle()
    val libTabs by libraryViewModel.tabs.collectAsStateWithLifecycle()
    val libTab by libraryViewModel.tab.collectAsStateWithLifecycle()
    val sessionState by libraryViewModel.sessionState.collectAsStateWithLifecycle()
    val feedState by feedViewModel.state.collectAsStateWithLifecycle()
    val player = LocalMusicPlayer.current
    val scope = rememberCoroutineScope()
    val libRefreshing by libraryViewModel.refreshing.collectAsStateWithLifecycle()
    val closeSearch = { searching = false; libraryViewModel.clearSearch() }
    BackHandler(enabled = active && searching) { closeSearch() }

    GlassHeaderLayout(
        modifier = Modifier.fillMaxSize(),
        header = { headerModifier, backdrop ->
            ExpressiveHeader(
                title = stringResource(R.string.feed_home),
                modifier = headerModifier,
                backdrop = backdrop,
                actions = {
                    if (searching) {
                        HeaderActionIcon(Icons.Filled.Close, "Close search", closeSearch)
                    } else {
                        JellyHeaderActions(
                            onSearch = { searching = true },
                            recognitionKey = "recognition_jelly_feed",
                            onRecognised = { q -> searching = true; libraryViewModel.setSearchQuery(q) },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                },
                bottomContent = {
                    if (searching) {
                        JellySearchField(search.query, libraryViewModel::setSearchQuery)
                    } else {
                        JellyChipsBar(SECTIONS, section, fill = true) { section = it }
                        if (section == 1) {
                            JellyChipsBar(libTabs.map { it.label }, libTabs.indexOf(libTab).coerceAtLeast(0)) {
                                libraryViewModel.selectTab(libTabs[it])
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                },
            )
        },
        content = { inset ->
            CompositionLocalProvider(LocalTopInset provides inset) {
                com.lastwave.app.ui.library.ProvideTrackDownloads {
                when {
                    searching -> JellySearchResults(
                        (sessionState as? LibrarySession.SignedIn)?.session, search, libraryViewModel,
                    )
                    section == 0 -> com.lastwave.app.ui.library.JellyPullRefresh(feedState.loading, feedViewModel::refresh) {
                        FeedSections(feedViewModel, libraryViewModel)
                    }
                    else -> com.lastwave.app.ui.library.JellyPullRefresh(libRefreshing, libraryViewModel::refresh) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                            ShuffleAllBar(inset) {
                                scope.launch {
                                    runCatching { feedViewModel.randomTracks() }.getOrNull()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { player.playQueue(it, 0, "Shuffle all", startShuffled = false) }
                                }
                            }
                            CompositionLocalProvider(LocalTopInset provides 0.dp) {
                                LibraryScreen(
                                    mode = LibraryMode.SERVER,
                                    embedded = true,
                                    active = active,
                                    viewModel = libraryViewModel,
                                )
                            }
                        }
                    }
                }
                }
            }
        },
    )
}

@Composable
private fun FeedSections(feedViewModel: JellyfinFeedViewModel, libraryViewModel: LibraryViewModel) {
    val state by feedViewModel.state.collectAsStateWithLifecycle()
    val player = LocalMusicPlayer.current
    val scope = rememberCoroutineScope()
    val session = state.session
    val feedContext = androidx.compose.ui.platform.LocalContext.current
    var orderVersion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var rearranging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Not state on purpose: it's only read when the dialog opens, and writing state while the list builds would loop.
    val allSections = androidx.compose.runtime.remember { arrayOfNulls<List<FeedSection>>(1) }
    @Suppress("UNUSED_VARIABLE") val readOrder = orderVersion
    if (rearranging) RearrangeFeedDialog(allSections[0].orEmpty()) { rearranging = false; orderVersion++ }
    val outsideVm: com.lastwave.app.ui.library.OutsideSourcesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val outsideRecs by outsideVm.recommendations.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.online, state.session) {
        if (state.online && state.session != null) outsideVm.loadRecommendations()
    }
    com.lastwave.app.ui.library.CollectionActionsHost(libraryViewModel, canAddToPlaylist = true)

    when {
        !state.sessionLoaded -> CenteredProgress()
        session == null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Not signed in to Jellyfin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = FloatingNavDefaults.contentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "tiles") {
                // Liked songs, then what you play most and most recently; falls back to new albums on a fresh install.
                val candidates = (state.mostPlayed + state.recentlyPlayed + state.recentlyAdded).distinctBy { it.id }
                val total = 1 + candidates.size.coerceAtMost(5)
                val count = if (total >= 2) minOf(6, total - total % 2) else 1
                val tiles = buildList {
                    add(FeedTile("Liked songs", null, heart = true, onClick = { libraryViewModel.open(LibraryDetail.LikedSongs()) }))
                    candidates.take(count - 1).forEach { item ->
                        val detail = detailOf(item, false)
                        add(
                            FeedTile(
                                title = item.name.orEmpty(),
                                coverUrl = libraryViewModel.coverUrl(session, item),
                                onClick = { libraryViewModel.open(detail) },
                                onLongClick = { libraryViewModel.showActions(detail) },
                            ),
                        )
                    }
                }
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 10.dp)) { FeedTilesGrid(tiles) }
            }

            val offlineMode = !state.online || (state.error != null && state.recentlyAdded.isEmpty())
            if (offlineMode && state.offlineItems.isNotEmpty()) {
                item(key = "offline-note") {
                    Text(
                        if (state.online) "Can't reach your server. Your downloads still work:"
                        else "You're offline. Your downloads still work:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "offline-shelf") {
                    AlbumShelf("Available offline", state.offlineItems, feedViewModel, session, libraryViewModel)
                }
            } else if (state.error != null && state.recentlyAdded.isEmpty()) {
                item(key = "error") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        ErrorWithRetry(state.error!!, feedViewModel::refresh)
                    }
                }
            } else if (state.loading && state.recentlyAdded.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CenteredProgressInline() }
                }
            } else {
                // Every shelf is a section so the order can be rearranged (saved per device).
                val sections = buildList {
                    state.shelves.filter { it.order < 50 && !it.artists }.forEach { shelf ->
                        add(FeedSection("s_${shelf.key}", shelf.title) { AlbumShelf(shelf.title, shelf.items, feedViewModel, session, libraryViewModel, round = shelf.artists) })
                    }
                    add(FeedSection("recent", "Recently added") { AlbumShelf("Recently added", state.recentlyAdded, feedViewModel, session, libraryViewModel) })
                    state.shelves.filter { it.order >= 50 && !it.artists }.forEach { shelf ->
                        add(FeedSection("s_${shelf.key}", shelf.title) { AlbumShelf(shelf.title, shelf.items, feedViewModel, session, libraryViewModel, round = shelf.artists) })
                    }
                    add(FeedSection("random", "Random picks") { AlbumShelf("Random picks", state.randomPicks, feedViewModel, session, libraryViewModel) })
                    state.shelves.filter { it.artists }.forEach { shelf ->
                        add(FeedSection("a_${shelf.key}", shelf.title) { AlbumShelf(shelf.title, shelf.items, feedViewModel, session, libraryViewModel, round = true) })
                    }
                    if (state.online) {
                        add(FeedSection("youtube", "YouTube picks") { com.lastwave.app.ui.library.YouTubeShelf(outsideRecs, player) })
                        add(FeedSection("discover", "Discover (request server)") { DiscoverShelves() })
                        add(FeedSection("listenbrainz", "ListenBrainz picks") { ListenBrainzShelf() })
                        add(FeedSection("lastfm", "Because you listen to… (Last.fm)") { LastFmShelf() })
                    }
                }
                val arranged = FeedOrder.arrange(feedContext, sections)
                allSections[0] = arranged
                val hiddenIds = FeedOrder.hidden(feedContext)
                items(arranged.filter { it.id !in hiddenIds }, key = { it.id }) { it.content() }
                item(key = "rearrange") {
                    androidx.compose.material3.TextButton(onClick = { rearranging = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Rearrange home") }
                }
            }
        }
    }
}

private fun detailOf(item: JfItem, round: Boolean): LibraryDetail = when {
    round -> LibraryDetail.Artist(item)
    item.type == "Playlist" -> LibraryDetail.Playlist(item)
    else -> LibraryDetail.Album(item)
}

@Composable
private fun CenteredProgressInline() {
    androidx.compose.material3.CircularProgressIndicator()
}

@Composable
private fun AlbumShelf(
    title: String,
    albums: List<JfItem>,
    feedViewModel: JellyfinFeedViewModel,
    session: com.lastwave.app.data.jellyfin.JellyfinSession,
    libraryViewModel: LibraryViewModel,
    round: Boolean = false,
) {
    if (albums.isEmpty()) return
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                CoverCard(
                    title = album.name.orEmpty(),
                    subtitle = if (round) null else album.albumArtist,
                    coverUrl = feedViewModel.coverUrl(session, album),
                    round = round,
                    onClick = { libraryViewModel.open(detailOf(album, round)) },
                    onLongClick = { libraryViewModel.showActions(detailOf(album, round)) },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}
