package com.lastwave.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.ExpressiveHeader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.playlist.PlaylistScreen
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape

/**
 * Jellyfin library browser.
 *
 *  - [LibraryMode.SAVED]: the Library tab. Playlists first (Liked songs pinned on top),
 *    then saved albums and saved artists, plus the phone's own music.
 *  - [LibraryMode.SERVER]: everything on the server (all albums, artists, songs), shown
 *    under the Library chip of the Feed tab. [embedded] drops the title and top inset
 *    because the host already draws a header.
 *
 * [active] says whether this copy is the one on screen, so only it handles Back.
 */
@Composable
fun LibraryScreen(
    mode: LibraryMode = LibraryMode.SAVED,
    embedded: Boolean = false,
    active: Boolean = true,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenFeedPlaylist: (String) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(key = "library-${mode.name}"),
) {
    // Set before the first frame so there is no flash of an empty chip row.
    remember(viewModel, mode) { viewModel.start(mode); true }

    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val detail by viewModel.currentDetail.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()

    val signedIn = sessionState as? LibrarySession.SignedIn

    // Load the visible tab once signed in, and again whenever the tab changes.
    LaunchedEffect(signedIn != null, tab) { viewModel.ensureLoaded() }

    // Back leaves a detail page first.
    BackHandler(enabled = active && detail != null) { viewModel.back() }

    val topModifier = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()
    Box(Modifier.fillMaxSize()) {
        when {
            sessionState is LibrarySession.Loading -> CenteredProgress()
            signedIn != null && detail != null ->
                DetailPage(signedIn.session, detail!!, viewModel, topModifier)
            else -> Column(Modifier.fillMaxSize()) {
                LibraryHeader(viewModel, embedded, onOpenSettings, onOpenDownloads)
                when {
                    tab == LibraryTab.PHONE -> PlaylistScreen(
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenFeedPlaylist = onOpenFeedPlaylist,
                        onOpenGenerator = onOpenGenerator,
                    )
                    signedIn == null -> SignedOutNotice()
                    else -> JellyfinTabContent(signedIn.session, tab, viewModel)
                }
            }
        }
    }
}

// ---- header and tab chips -----------------------------------------------------------------

@Composable
private fun LibraryHeader(
    viewModel: LibraryViewModel,
    embedded: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val chips: @Composable () -> Unit = {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs) { entry ->
                FilterChip(
                    selected = entry == tab,
                    onClick = { viewModel.selectTab(entry) },
                    label = { Text(entry.label) },
                )
            }
        }
    }
    if (embedded) {
        // The host (Feed) already draws the header; only the chips are ours.
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            chips()
            Spacer(Modifier.height(8.dp))
        }
        return
    }
    ExpressiveHeader(
        title = "Library",
        actions = {
            JellyHeaderActions(
                onRefresh = { if (tab != LibraryTab.PHONE) viewModel.refresh() },
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
            )
        },
        bottomContent = {
            chips()
            Spacer(Modifier.height(8.dp))
        },
    )
}

/**
 * The action row shared by Feed and Library so both headers match:
 * Refresh, Downloads, then the profile avatar that opens Settings.
 */
@Composable
internal fun JellyHeaderActions(
    onRefresh: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    avatarUrl: String? = null,
) {
    HeaderActionIcon(Icons.Filled.Refresh, "Refresh", onRefresh)
    HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
    IconButton(onClick = onOpenSettings) {
        com.lastwave.app.ui.home.ProfileAvatar(avatarUrl = avatarUrl, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun SignedOutNotice() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Sign in to Jellyfin to browse your server. Your phone's own music is under “On this phone”.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ---- Jellyfin lists ----------------------------------------------------------------------

@Composable
private fun JellyfinTabContent(session: JellyfinSession, tab: LibraryTab, viewModel: LibraryViewModel) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val saved = mode == LibraryMode.SAVED
    when (tab) {
        LibraryTab.PLAYLISTS -> {
            val state by viewModel.playlists.collectAsStateWithLifecycle()
            PlaylistsGrid(session, state, viewModel)
        }
        LibraryTab.ALBUMS -> {
            val state by viewModel.albums.collectAsStateWithLifecycle()
            CoverGrid(
                session = session,
                state = state,
                viewModel = viewModel,
                round = false,
                emptyText = if (saved) "Heart an album to save it here" else "No albums found",
                onLoadMore = viewModel::loadMoreAlbums,
                onRetry = viewModel::refresh,
                onClick = { viewModel.open(LibraryDetail.Album(it)) },
                subtitleOf = { it.albumArtist },
            )
        }
        LibraryTab.ARTISTS -> {
            val state by viewModel.artists.collectAsStateWithLifecycle()
            CoverGrid(
                session = session,
                state = state,
                viewModel = viewModel,
                round = true,
                emptyText = if (saved) "Heart an artist to save them here" else "No artists found",
                onLoadMore = viewModel::loadMoreArtists,
                onRetry = viewModel::refresh,
                onClick = { viewModel.open(LibraryDetail.Artist(it)) },
                subtitleOf = { null },
            )
        }
        LibraryTab.SONGS -> AllSongsList(viewModel)
        LibraryTab.PHONE -> Unit
    }
}

@Composable
private fun PlaylistsGrid(session: JellyfinSession, state: ListState, viewModel: LibraryViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, top = 4.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Pinned first: Liked songs.
        item(key = "liked-songs") {
            LikedSongsCard(onClick = { viewModel.open(LibraryDetail.LikedSongs()) })
        }
        items(state.items, key = { it.id }) { item ->
            CoverCard(
                title = item.name.orEmpty(),
                subtitle = item.childCount?.let { "$it songs" },
                coverUrl = viewModel.coverUrl(session, item),
                round = false,
                onClick = { viewModel.open(LibraryDetail.Playlist(item)) },
            )
        }
        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                ErrorWithRetry(state.error, viewModel::refresh)
            }
        }
    }
}

@Composable
private fun LikedSongsCard(onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Liked songs", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Pinned",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CoverGrid(
    session: JellyfinSession,
    state: ListState,
    viewModel: LibraryViewModel,
    round: Boolean,
    emptyText: String,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClick: (JfItem) -> Unit,
    subtitleOf: (JfItem) -> String?,
) {
    val gridState = rememberLazyGridState()
    GridLoadMoreEffect(gridState, state.items.size, state.hasMore && !state.loading && state.error == null, onLoadMore)

    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            when {
                state.loading || !state.loaded -> CircularProgressIndicator()
                state.error != null -> ErrorWithRetry(state.error, onRetry)
                else -> Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, top = 4.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.items, key = { it.id }) { item ->
            CoverCard(
                title = item.name.orEmpty(),
                subtitle = subtitleOf(item),
                coverUrl = viewModel.coverUrl(session, item),
                round = round,
                onClick = { onClick(item) },
            )
        }
        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") { ErrorWithRetry(state.error, onLoadMore) }
        }
    }
}

@Composable
private fun GridLoadMoreEffect(state: LazyGridState, itemCount: Int, canLoad: Boolean, onLoadMore: () -> Unit) {
    val nearEnd by remember(state, itemCount, canLoad) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            canLoad && last >= itemCount - 12
        }
    }
    LaunchedEffect(nearEnd, itemCount) { if (nearEnd) onLoadMore() }
}

@Composable
private fun ListLoadMoreEffect(state: LazyListState, itemCount: Int, canLoad: Boolean, onLoadMore: () -> Unit) {
    val nearEnd by remember(state, itemCount, canLoad) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            canLoad && last >= itemCount - 15
        }
    }
    LaunchedEffect(nearEnd, itemCount) { if (nearEnd) onLoadMore() }
}

@Composable
internal fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
internal fun CoverCard(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        CoverImage(
            url = coverUrl,
            shape = if (round) CircleShape else RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CoverImage(url: String?, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        if (url != null) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- all songs (server mode) -------------------------------------------------------------------

@Composable
private fun AllSongsList(viewModel: LibraryViewModel) {
    val state by viewModel.songs.collectAsStateWithLifecycle()
    val player = LocalMusicPlayer.current
    val listState = rememberLazyListState()
    ListLoadMoreEffect(listState, state.tracks.size, state.hasMore && !state.loading && state.error == null, viewModel::loadMoreSongs)

    if (state.tracks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            when {
                state.loading || !state.loaded -> CircularProgressIndicator()
                state.error != null -> ErrorWithRetry(state.error!!, viewModel::refresh)
                else -> Text("No songs found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingNavDefaults.contentBottomPadding()),
    ) {
        itemsIndexed(state.tracks, key = { index, track -> "${index}_${track.internalTrackId}" }) { index, track ->
            TrackRow(track, showNumber = false) {
                // The queue is what has loaded so far, starting at the tapped song.
                player.playQueue(state.tracks, index, "All songs", startShuffled = false)
            }
        }
        if (state.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (state.error != null) {
            item(key = "error") { ErrorWithRetry(state.error!!, viewModel::loadMoreSongs) }
        }
    }
}

// ---- detail pages ----------------------------------------------------------------------------

@Composable
private fun DetailPage(session: JellyfinSession, detail: LibraryDetail, viewModel: LibraryViewModel, modifier: Modifier) {
    val item = detail.item
    val overrides by viewModel.favouriteOverrides.collectAsStateWithLifecycle()
    val isFavourite = overrides[item.id] ?: item.isFavorite
    val canFavourite = detail is LibraryDetail.Album || detail is LibraryDetail.Artist

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            if (canFavourite) {
                IconButton(onClick = { viewModel.toggleFavourite(item) }) {
                    Icon(
                        if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavourite) "Remove from saved" else "Save",
                    )
                }
            }
        }
        when (detail) {
            is LibraryDetail.Artist -> ArtistAlbums(session, detail, viewModel)
            else -> {
                val tracksState by viewModel.detailTracks.collectAsStateWithLifecycle()
                val subtitle = when (detail) {
                    is LibraryDetail.Album -> listOfNotNull(item.albumArtist, item.year?.toString()).joinToString(" · ")
                    is LibraryDetail.LikedSongs -> "${tracksState.tracks.size} songs"
                    else -> ""
                }
                TrackList(
                    state = tracksState,
                    sourceLabel = item.name.orEmpty(),
                    emptyText = if (detail is LibraryDetail.LikedSongs) "Heart a song to see it here" else "No songs here",
                    onRetry = viewModel::reloadDetail,
                    header = {
                        DetailHeader(
                            title = item.name.orEmpty(),
                            subtitle = subtitle,
                            coverUrl = viewModel.coverUrl(session, item, 600),
                            heart = detail is LibraryDetail.LikedSongs,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistAlbums(session: JellyfinSession, detail: LibraryDetail.Artist, viewModel: LibraryViewModel) {
    val state by viewModel.artistAlbums.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = detail.item.name.orEmpty(),
            subtitle = "",
            coverUrl = viewModel.coverUrl(session, detail.item, 600),
            round = true,
        )
        when {
            state.loading -> CenteredProgress()
            state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                ErrorWithRetry(state.error!!, viewModel::reloadDetail)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp,
                    bottom = FloatingNavDefaults.contentBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.albums, key = { it.id }) { album ->
                    CoverCard(
                        title = album.name.orEmpty(),
                        subtitle = album.year?.toString(),
                        coverUrl = viewModel.coverUrl(session, album),
                        round = false,
                        onClick = { viewModel.open(LibraryDetail.Album(album)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    round: Boolean = false,
    heart: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (heart) {
            Box(
                Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        } else {
            CoverImage(
                url = coverUrl,
                shape = if (round) CircleShape else RoundedCornerShape(16.dp),
                modifier = Modifier.size(112.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- track list (album, playlist, liked songs) -----------------------------------------------

@Composable
private fun TrackList(
    state: TracksState,
    sourceLabel: String,
    emptyText: String,
    onRetry: () -> Unit,
    header: (@Composable () -> Unit)?,
) {
    val player = LocalMusicPlayer.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingNavDefaults.contentBottomPadding()),
    ) {
        if (header != null) item(key = "header") { header() }

        if (state.tracks.isNotEmpty()) {
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { player.playQueue(state.tracks, 0, sourceLabel, startShuffled = false) },
                        shape = ExpressivePillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Play")
                    }
                    OutlinedButton(
                        onClick = { player.playQueue(state.tracks, 0, sourceLabel, startShuffled = true) },
                        shape = ExpressivePillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Shuffle")
                    }
                }
            }
        }

        when {
            state.loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> item(key = "error") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    ErrorWithRetry(state.error, onRetry)
                }
            }
            state.tracks.isEmpty() -> item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> itemsIndexed(state.tracks, key = { index, track -> "${index}_${track.internalTrackId}" }) { index, track ->
                TrackRow(track) { player.playQueue(state.tracks, index, sourceLabel, startShuffled = false) }
            }
        }
    }
}

@Composable
private fun TrackRow(track: PlayableTrack, showNumber: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                track.trackNumber?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(32.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        track.durationMs?.let { ms ->
            Text(
                formatDuration(ms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
