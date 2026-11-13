package com.lastwave.app.ui.library

import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.GlassHeaderLayout
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import com.lastwave.app.ui.common.ProfileAvatarViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import android.widget.Toast
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

    // Playlist editing: songs waiting for a playlist to be picked, and the "new playlist" dialog.
    var pickerTracks by remember { mutableStateOf<List<PlayableTrack>?>(null) }
    var createWith by remember { mutableStateOf<List<PlayableTrack>?>(null) }
    val context = LocalContext.current
    LaunchedEffect(viewModel, active) {
        if (active) viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val openPicker: (List<PlayableTrack>) -> Unit = { tracks ->
        viewModel.refreshPlaylists()
        pickerTracks = tracks
    }

    pickerTracks?.let { tracks ->
        PlaylistPickerDialog(
            viewModel = viewModel,
            onPick = { viewModel.addToPlaylist(it, tracks); pickerTracks = null },
            onNew = { createWith = tracks; pickerTracks = null },
            onDismiss = { pickerTracks = null },
        )
    }
    createWith?.let { seed ->
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { viewModel.createPlaylist(it, seed); createWith = null },
            onDismiss = { createWith = null },
        )
    }

    CompositionLocalProvider(LocalJellyAddToPlaylist provides openPicker) {
    Box(Modifier.fillMaxSize()) {
        when {
            sessionState is LibrarySession.Loading -> CenteredProgress()
            signedIn != null && detail != null ->
                DetailPage(signedIn.session, detail!!, viewModel, topModifier)
            embedded -> LibraryBody(tab, signedIn, viewModel, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator)
            else -> LibraryPage(
                viewModel, tab, signedIn, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator,
                onOpenSettings, onOpenDownloads, onCreatePlaylist = { createWith = emptyList() },
            )
        }
    }
    }
}

internal val LocalJellyAddToPlaylist = staticCompositionLocalOf<(List<PlayableTrack>) -> Unit> { {} }

// ---- header and tab chips -----------------------------------------------------------------

@Composable
private fun LibraryPage(
    viewModel: LibraryViewModel,
    tab: LibraryTab,
    signedIn: LibrarySession.SignedIn?,
    onOpenPlaylist: (Long) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenGenerator: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val closeSearch = { searching = false; viewModel.clearSearch() }
    BackHandler(enabled = searching) { closeSearch() }

    GlassHeaderLayout(
        modifier = Modifier.fillMaxSize(),
        header = { headerModifier, backdrop ->
            ExpressiveHeader(
                title = "Library",
                modifier = headerModifier,
                backdrop = backdrop,
                actions = {
                    if (searching) {
                        HeaderActionIcon(Icons.Filled.Close, "Close search", closeSearch)
                    } else {
                        if (mode == LibraryMode.SAVED && tab == LibraryTab.PLAYLISTS) {
                            HeaderActionIcon(Icons.Filled.Add, "New playlist", onCreatePlaylist)
                        }
                        JellyHeaderActions(
                            onRefresh = { if (tab != LibraryTab.PHONE) viewModel.refresh() },
                            onSearch = { searching = true },
                            onOpenDownloads = onOpenDownloads,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                },
                bottomContent = {
                    if (searching) {
                        JellySearchField(search.query, viewModel::setSearchQuery)
                    } else {
                        JellyChipsBar(tabs.map { it.label }, tabs.indexOf(tab).coerceAtLeast(0)) {
                            viewModel.selectTab(tabs[it])
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                },
            )
        },
        content = { inset ->
            CompositionLocalProvider(LocalTopInset provides inset) {
                if (searching) {
                    JellySearchResults(signedIn?.session, search, viewModel)
                } else {
                    LibraryBody(tab, signedIn, viewModel, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator)
                }
            }
        },
    )
}

@Composable
private fun LibraryBody(
    tab: LibraryTab,
    signedIn: LibrarySession.SignedIn?,
    viewModel: LibraryViewModel,
    onOpenPlaylist: (Long) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenGenerator: () -> Unit,
) {
    when {
        tab == LibraryTab.PHONE -> Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current)) {
            PlaylistScreen(
                onOpenPlaylist = onOpenPlaylist,
                onOpenFeedPlaylist = onOpenFeedPlaylist,
                onOpenGenerator = onOpenGenerator,
                showHeader = false,
            )
        }
        signedIn == null -> SignedOutNotice()
        else -> JellyfinTabContent(signedIn.session, tab, viewModel)
    }
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
    onSearch: () -> Unit,
) {
    val avatarUrl by hiltViewModel<ProfileAvatarViewModel>().avatarUrl.collectAsStateWithLifecycle()
    HeaderActionIcon(Icons.Filled.Refresh, "Refresh", onRefresh)
    HeaderActionIcon(Icons.Filled.Search, "Search", onSearch)
    HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
    IconButton(onClick = onOpenSettings) {
        com.lastwave.app.ui.home.ProfileAvatar(avatarUrl = avatarUrl, modifier = Modifier.size(38.dp))
    }
}

@Composable
internal fun SignedOutNotice() {
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
            start = 12.dp, end = 12.dp, top = 4.dp + LocalTopInset.current,
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
            start = 12.dp, end = 12.dp, top = 4.dp + LocalTopInset.current,
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
        contentPadding = PaddingValues(top = LocalTopInset.current, bottom = FloatingNavDefaults.contentBottomPadding()),
    ) {
        itemsIndexed(state.tracks, key = { index, track -> "${index}_${track.internalTrackId}" }) { index, track ->
            val addToPlaylist = LocalJellyAddToPlaylist.current
            JellyTrackRow(
                track,
                groupPositionFor(index, state.tracks.size),
                actions = listOf(RowAction("Add to playlist") { addToPlaylist(listOf(track)) }),
            ) {
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
    val addToPlaylist = LocalJellyAddToPlaylist.current
    val tracksNow by viewModel.detailTracks.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (renaming) {
        NameDialog(
            title = "Rename playlist",
            initial = item.name.orEmpty(),
            confirmLabel = "Save",
            onConfirm = { viewModel.renameCurrentPlaylist(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("“${item.name.orEmpty()}” will be deleted from your Jellyfin server. The songs themselves stay in your library.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteCurrentPlaylist() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

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
            if (detail is LibraryDetail.Playlist || detail is LibraryDetail.Album) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (detail is LibraryDetail.Album) {
                            DropdownMenuItem(
                                text = { Text("Add album to playlist") },
                                enabled = tracksNow.tracks.isNotEmpty(),
                                onClick = { menuOpen = false; addToPlaylist(tracksNow.tracks) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menuOpen = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
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
                    showNumbers = detail is LibraryDetail.Album,
                    emptyText = if (detail is LibraryDetail.LikedSongs) "Heart a song to see it here" else "No songs here",
                    onRetry = viewModel::reloadDetail,
                    rowActions = { index, track ->
                        buildList {
                            add(RowAction("Add to playlist") { addToPlaylist(listOf(track)) })
                            when (detail) {
                                is LibraryDetail.Playlist -> {
                                    if (index > 0) add(RowAction("Move up") { viewModel.moveInPlaylist(index, -1) })
                                    if (index < tracksState.tracks.lastIndex) {
                                        add(RowAction("Move down") { viewModel.moveInPlaylist(index, 1) })
                                    }
                                    add(RowAction("Remove from playlist") { viewModel.removeFromPlaylist(index) })
                                }
                                is LibraryDetail.LikedSongs ->
                                    add(RowAction("Remove from Liked songs") { viewModel.removeFromLiked(index) })
                                else -> Unit
                            }
                        }
                    },
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
    showNumbers: Boolean,
    emptyText: String,
    onRetry: () -> Unit,
    rowActions: (index: Int, track: PlayableTrack) -> List<RowAction>,
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
                JellyTrackRow(
                    track,
                    groupPositionFor(index, state.tracks.size),
                    number = if (showNumbers) track.trackNumber else null,
                    actions = rowActions(index, track),
                ) {
                    player.playQueue(state.tracks, index, sourceLabel, startShuffled = false)
                }
            }
        }
    }
}

// ---- playlist dialogs ---------------------------------------------------------------------------

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlaylistPickerDialog(
    viewModel: LibraryViewModel,
    onPick: (JfItem) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item(key = "new") {
                    Text(
                        "New playlist…",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 14.dp),
                    )
                }
                if (state.loading && state.items.isEmpty()) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                state.error?.let { message ->
                    item(key = "error") {
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                items(state.items, key = { it.id }) { playlist ->
                    Text(
                        playlist.name.orEmpty().ifBlank { "Untitled" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(playlist) }.padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
