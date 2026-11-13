package com.lastwave.app.ui.feed

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

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(
            title = stringResource(R.string.feed_home),
            actions = {
                JellyHeaderActions(
                    onRefresh = if (section == 0) feedViewModel::refresh else libraryViewModel::refresh,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                )
            },
            bottomContent = {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SECTIONS.size) { index ->
                        FilterChip(
                            selected = section == index,
                            onClick = { section = index },
                            label = { Text(SECTIONS[index]) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            },
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section == 0) {
                FeedSections(feedViewModel, libraryViewModel)
            } else {
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

@Composable
private fun FeedSections(feedViewModel: JellyfinFeedViewModel, libraryViewModel: LibraryViewModel) {
    val state by feedViewModel.state.collectAsStateWithLifecycle()
    val player = LocalMusicPlayer.current
    val scope = rememberCoroutineScope()
    val session = state.session

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
            contentPadding = PaddingValues(bottom = FloatingNavDefaults.contentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { feedViewModel.randomTracks() }.getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { player.playQueue(it, 0, "Shuffle all", startShuffled = false) }
                            }
                        },
                        shape = ExpressivePillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Shuffle all")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { feedViewModel.likedTracks() }.getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { player.playQueue(it, 0, "Liked songs", startShuffled = true) }
                            }
                        },
                        shape = ExpressivePillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Liked songs")
                    }
                }
            }

            if (state.error != null && state.recentlyAdded.isEmpty()) {
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
                item(key = "recent") {
                    AlbumShelf("Recently added", state.recentlyAdded, feedViewModel, session, libraryViewModel)
                }
                item(key = "random") {
                    AlbumShelf("Random picks", state.randomPicks, feedViewModel, session, libraryViewModel)
                }
            }
        }
    }
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
                    subtitle = album.albumArtist,
                    coverUrl = feedViewModel.coverUrl(session, album),
                    round = false,
                    onClick = { libraryViewModel.open(LibraryDetail.Album(album)) },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}
