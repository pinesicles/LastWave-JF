package com.lastwave.app.ui.library

import com.lastwave.app.data.jellyfin.download.COLLECTION_PLAYLIST
import com.lastwave.app.data.jellyfin.download.COLLECTION_ALBUM
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.ExpressiveHeader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.rounded.MyLocation
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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

    var showImport by rememberSaveable { mutableStateOf(false) }
    if (showImport) {
        JellyImportDialog(hiltViewModel()) { created ->
            showImport = false
            if (created) viewModel.refreshPlaylists()
        }
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
    ProvideTrackDownloads {
    CollectionActionsHost(viewModel, canAddToPlaylist = true)
    Box(Modifier.fillMaxSize()) {
        when {
            sessionState is LibrarySession.Loading -> CenteredProgress()
            signedIn != null && detail != null ->
                DetailPage(signedIn.session, detail!!, viewModel, Modifier.fillMaxSize(), applyStatusBar = !embedded)
            embedded -> LibraryBody(tab, signedIn, viewModel, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator)
            else -> LibraryPage(
                viewModel, tab, signedIn, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator,
                onOpenSettings, onOpenDownloads, onCreatePlaylist = { createWith = emptyList() },
                onImportPlaylist = { showImport = true },
            )
        }
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
    onImportPlaylist: () -> Unit,
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
                            var addMenu by remember { mutableStateOf(false) }
                            Box {
                                HeaderActionIcon(Icons.Filled.Add, "New or import playlist") { addMenu = true }
                                androidx.compose.material3.DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("New playlist") },
                                        onClick = { addMenu = false; onCreatePlaylist() },
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Import playlist") },
                                        onClick = { addMenu = false; onImportPlaylist() },
                                    )
                                }
                            }
                        }
                        JellyHeaderActions(
                            onSearch = { searching = true },
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
                    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
                    JellyPullRefresh(refreshing, { if (tab != LibraryTab.PHONE) viewModel.refresh() }) {
                        LibraryBody(tab, signedIn, viewModel, onOpenPlaylist, onOpenFeedPlaylist, onOpenGenerator)
                    }
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
 * The action row shared by Feed and Library: Search, (Feed: identify music), then the profile avatar
 * that opens Settings. Refresh is pull-down; Downloads open themselves when offline.
 */
@Composable
internal fun JellyHeaderActions(
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit,
    /** Feed only: the identify-music button. Null leaves it out. */
    recognitionKey: String? = null,
    onRecognised: (String) -> Unit = {},
) {
    val avatarUrl by hiltViewModel<ProfileAvatarViewModel>().avatarUrl.collectAsStateWithLifecycle()
    HeaderActionIcon(Icons.Filled.Search, "Search", onSearch)
    if (recognitionKey != null) {
        com.lastwave.app.ui.recognition.MusicRecognitionButton(key = recognitionKey, onMatchSearch = onRecognised)
    }
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
        LibraryTab.DOWNLOADED -> DownloadedGrid(session, viewModel)
        LibraryTab.PHONE -> Unit
    }
}

@Composable
private fun DownloadedGrid(session: JellyfinSession, viewModel: LibraryViewModel) {
    val collections by viewModel.downloadedCollections.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
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
        item(key = "wifi", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Download on Wi-Fi only", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
            }
        }
        if (collections.isEmpty()) {
            item(key = "empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "Nothing downloaded yet. Open an album or playlist and tap the download button.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(collections.sortedBy { if (it.id in pins) 0 else 1 }, key = { it.id }) { c ->
            val p = progress[c.id]
            val item = JfItem(id = c.id, name = c.name, albumArtist = c.subtitle)
            CoverCard(
                title = c.name,
                subtitle = if (p != null && p.done < p.total) "Downloading ${p.done}/${p.total}"
                else c.subtitle ?: if (c.type == COLLECTION_PLAYLIST) "Playlist" else null,
                coverUrl = viewModel.coverUrl(session, item),
                round = false,
                onClick = {
                    viewModel.openFromDownloads(
                        if (c.type == COLLECTION_ALBUM) LibraryDetail.Album(item) else LibraryDetail.Playlist(item),
                        c.id,
                    )
                },
                onLongClick = {
                    viewModel.showActions(if (c.type == COLLECTION_ALBUM) LibraryDetail.Album(item) else LibraryDetail.Playlist(item))
                },
                pinned = c.id in pins,
            )
        }
    }
}

@Composable
private fun PlaylistsGrid(session: JellyfinSession, state: ListState, viewModel: LibraryViewModel) {
    val pins by viewModel.pins.collectAsStateWithLifecycle()
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
        val ordered = state.items.sortedBy { if (it.id in pins) 0 else 1 }
        items(ordered, key = { it.id }) { item ->
            CoverCard(
                title = item.name.orEmpty(),
                subtitle = item.childCount?.let { "$it songs" },
                coverUrl = viewModel.coverUrl(session, item),
                round = false,
                onClick = { viewModel.open(LibraryDetail.Playlist(item)) },
                onLongClick = { viewModel.showActions(LibraryDetail.Playlist(item)) },
                pinned = item.id in pins,
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
    val pins by viewModel.pins.collectAsStateWithLifecycle()
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
        val ordered = state.items.sortedBy { if (it.id in pins) 0 else 1 }
        items(ordered, key = { it.id }) { item ->
            CoverCard(
                title = item.name.orEmpty(),
                subtitle = subtitleOf(item),
                coverUrl = viewModel.coverUrl(session, item),
                round = round,
                onClick = { onClick(item) },
                onLongClick = { viewModel.showActions(if (round) LibraryDetail.Artist(item) else LibraryDetail.Album(item)) },
                pinned = item.id in pins,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverCard(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    pinned: Boolean = false,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { long ->
                    {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        long()
                    }
                },
            )
            .padding(4.dp),
    ) {
        Box {
            CoverImage(
                url = coverUrl,
                shape = if (round) CircleShape else RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
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
private fun DetailPage(session: JellyfinSession, detail: LibraryDetail, viewModel: LibraryViewModel, modifier: Modifier, applyStatusBar: Boolean) {
    val item = detail.item
    val overrides by viewModel.favouriteOverrides.collectAsStateWithLifecycle()
    val isFavourite = overrides[item.id] ?: item.isFavorite
    val canFavourite = detail is LibraryDetail.Artist
    val addToPlaylist = LocalJellyAddToPlaylist.current
    val isPlaylistPage = detail is LibraryDetail.Playlist
    var showSyncIssues by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showSyncIssues) SyncIssuesHost { showSyncIssues = false }
    var showLeft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showLeft) ImportLeftoversDialog(item.id) { showLeft = false }
    androidx.compose.runtime.DisposableEffect(item.id, isPlaylistPage) {
        // Albums requested while this playlist is open get added to it once they reach the server.
        if (isPlaylistPage) com.lastwave.app.data.requests.RequestContext.playlist = item.id to (item.name ?: "playlist")
        onDispose {
            if (com.lastwave.app.data.requests.RequestContext.playlist?.first == item.id) com.lastwave.app.data.requests.RequestContext.playlist = null
        }
    }
    val tracksNow by viewModel.detailTracks.collectAsStateWithLifecycle()
    val artistState by viewModel.artistAlbums.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pastHero by remember {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > with(density) { 260.dp.toPx() }
        }
    }
    val player = LocalMusicPlayer.current
    val chromeNow by player.chromeState.collectAsStateWithLifecycle()
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    val loadingNow = tracksNow.loading || tracksNow.refreshing || (detail is LibraryDetail.Artist && artistState.loading)
    var menuOpen by remember { mutableStateOf(false) }
    var searching by rememberSaveable(item.id) { mutableStateOf(false) }
    var query by rememberSaveable(item.id) { mutableStateOf("") }
    BackHandler(enabled = searching) { searching = false; query = "" }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmRemoveDownload by remember { mutableStateOf(false) }
    val downloaded by viewModel.downloadedCollections.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val coverTags by viewModel.coverTags.collectAsStateWithLifecycle()
    val collection = downloaded.firstOrNull { it.id == item.id }
    val progress = progressMap[item.id]
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) viewModel.changeCover(bytes, mime)
        }
    }
    val collectionType = if (detail is LibraryDetail.Album) COLLECTION_ALBUM else COLLECTION_PLAYLIST
    val downloadSlot: (@Composable () -> Unit)? =
        if (detail is LibraryDetail.Album || detail is LibraryDetail.Playlist || detail is LibraryDetail.LikedSongs) {
            @Composable {
                CollectionDownloadStatus(
                    itemId = item.id,
                    downloaded = collection != null,
                    done = progress?.done ?: 0,
                    total = progress?.total ?: 0,
                    failed = progress?.failed ?: 0,
                    viewModel = viewModel,
                    onClick = {
                        // Tapping again retries failed songs; otherwise offers to remove the download.
                        if (collection == null || (progress?.failed ?: 0) > 0) viewModel.downloadCollection(item, collectionType)
                        else confirmRemoveDownload = true
                    },
                )
            }
        } else null
    val editTrailing: (@Composable (Int) -> Unit)? =
        if (editing && detail is LibraryDetail.Playlist) {
            @Composable { index: Int ->
                Row {
                    IconButton(onClick = { viewModel.moveInPlaylist(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { viewModel.moveInPlaylist(index, 1) }, enabled = index < tracksNow.tracks.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                    IconButton(onClick = { viewModel.removeFromPlaylist(index) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
        } else null

    if (confirmRemoveDownload) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownload = false },
            title = { Text("Remove download?") },
            text = { Text("The songs are deleted from this phone unless another downloaded album or playlist uses them. Nothing changes on your server.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveDownload = false; viewModel.removeDownload(item.id) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownload = false }) { Text("Cancel") } },
        )
    }

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

    val collageSeed = remember { System.nanoTime() }
    val collage: List<String> = remember(tracksNow.tracks.size, detail is LibraryDetail.LikedSongs) {
        if (detail !is LibraryDetail.LikedSongs) emptyList()
        else tracksNow.tracks.mapNotNull { it.artworkUrl?.takeIf { u -> u.isNotBlank() } }.distinct()
            .shuffled(kotlin.random.Random(collageSeed)).take(18)
    }
    val heroCoverUrl = viewModel.coverUrl(session, item, 800) ?: collage.firstOrNull()
    val coverMain = rememberCoverColors(viewModel.coverUrl(session, item, 600) ?: collage.firstOrNull())
    // Liked songs: blend colours from several of the covers shown, so the gradient and heart match the collage.
    val likedB = rememberCoverColors(collage.getOrNull(1))
    val likedC = rememberCoverColors(collage.getOrNull(2))
    val cover = if (coverMain != null && detail is LibraryDetail.LikedSongs) {
        CoverColors(
            coverMain.top, (likedB ?: coverMain).top, coverMain.onColor, coverMain.onVariant,
            accent = (likedC ?: likedB ?: coverMain).accent,
        )
    } else coverMain
    val pageBackground = MaterialTheme.colorScheme.background
    androidx.compose.runtime.CompositionLocalProvider(
        LocalHeroCollage provides (collage.takeIf { it.isNotEmpty() }),
        LocalCoverColors provides cover,
        androidx.compose.material3.LocalContentColor provides (cover?.onColor ?: androidx.compose.material3.LocalContentColor.current),
    ) {
val overCover = (detail is LibraryDetail.Playlist && heroCoverUrl != null) || (detail is LibraryDetail.LikedSongs && collage.isNotEmpty())
    val showScrolledHeader = overCover && pastHero
    val locate: () -> Unit = {
        val playing = chromeNow.current?.internalTrackId
        val q = query.trim()
        val list = if (q.isEmpty()) tracksNow.tracks else tracksNow.tracks.filter { t ->
            t.title.contains(q, true) || t.artist.contains(q, true) || (t.album?.contains(q, true) == true)
        }
        val at = list.indexOfFirst { it.internalTrackId == playing }
        if (playing != null && at >= 0) pageScope.launch { listState.animateScrollToItem(at + 1) }
    }
    @Composable
    fun TopBar() {
        val floating = overCover && !showScrolledHeader
        val barTint = if (floating) Color.White else androidx.compose.material3.LocalContentColor.current
        val pillColor = if (floating) Color.Black.copy(alpha = 0.38f) else Color.Transparent
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (overCover) 12.dp else 4.dp, vertical = if (overCover) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { viewModel.back() },
                modifier = if (overCover) Modifier.size(42.dp).background(pillColor, CircleShape) else Modifier,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = barTint, modifier = Modifier.size(if (overCover) 22.dp else 24.dp))
            }
            // The search bar grows leftwards out of the search icon.
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterEnd) {
                // Scrolled past the header: the playlist's cover and name sit in the bar.
                if (showScrolledHeader && !searching) {
                    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(heroCoverUrl, RoundedCornerShape(8.dp), Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            item.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = barTint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = searching,
                    enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.End) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.End) + androidx.compose.animation.fadeOut(),
                ) {
                    PlaylistSearchField(query, { query = it }, onClose = { searching = false; query = "" })
                }
            }
            if (editing) TextButton(onClick = { editing = false }) { Text("Done") }
            // Loading indicator: the server is still sending songs. Sits beside the pill, not inside it.
            if (loadingNow) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = barTint,
                )
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = pillColor,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showScrolledHeader && tracksNow.tracks.isNotEmpty()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = locate,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Locate current song", modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(4.dp))
                    }
            if (!searching && (detail is LibraryDetail.Playlist || detail is LibraryDetail.Album || detail is LibraryDetail.LikedSongs)) {
                IconButton(onClick = { searching = true }, modifier = if (overCover) Modifier.size(42.dp) else Modifier) {
                    Icon(Icons.Filled.Search, contentDescription = "Search this list", tint = barTint)
                }
            }
            if (canFavourite || detail is LibraryDetail.Album || detail is LibraryDetail.Playlist) {
                IconButton(onClick = { viewModel.toggleFavourite(item) }, modifier = if (overCover) Modifier.size(42.dp) else Modifier) {
                    Icon(
                        if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavourite) "Remove from saved" else "Save",
                        tint = barTint,
                    )
                }
            }
            if (detail is LibraryDetail.Playlist || detail is LibraryDetail.Album || detail is LibraryDetail.LikedSongs) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = if (overCover) Modifier.size(42.dp) else Modifier) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = barTint)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (detail is LibraryDetail.LikedSongs) {
                            val failedCount by androidx.hilt.navigation.compose.hiltViewModel<SyncIssuesViewModel>(key = "sync-issues")
                                .mirror.failures.collectAsStateWithLifecycle()
                            DropdownMenuItem(
                                text = { Text(if (failedCount.isEmpty()) "Sync issues" else "Sync issues (${failedCount.size})") },
                                onClick = { menuOpen = false; showSyncIssues = true },
                            )
                        } else if (detail is LibraryDetail.Album) {
                            DropdownMenuItem(
                                text = { Text("Add album to playlist") },
                                enabled = tracksNow.tracks.isNotEmpty(),
                                onClick = { menuOpen = false; addToPlaylist(tracksNow.tracks) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Reorder songs") },
                                onClick = { menuOpen = false; editing = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menuOpen = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Change cover") },
                                onClick = { menuOpen = false; coverPicker.launch("image/*") },
                            )
                            val leftCount = remember(item.id, menuOpen) { ImportLeftovers.count(context, item.id) }
                            if (leftCount > 0) DropdownMenuItem(
                                text = { Text("Unmatched songs ($leftCount)") },
                                onClick = { menuOpen = false; showLeft = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Add all to another playlist") },
                                enabled = tracksNow.tracks.isNotEmpty(),
                                onClick = { menuOpen = false; addToPlaylist(tracksNow.tracks) },
                            )
                        }
                        if (collection != null) {
                            if (detail is LibraryDetail.Playlist) {
                                DropdownMenuItem(
                                    text = { Text(if (collection.autoSync) "Stop keeping in sync" else "Keep downloads in sync") },
                                    onClick = { menuOpen = false; viewModel.setKeepInSync(item.id, !collection.autoSync) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Remove download") },
                                onClick = { menuOpen = false; confirmRemoveDownload = true },
                            )
                        }
                        if (detail is LibraryDetail.Playlist) {
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                }
            }
        
                }
            }
        }
    }
    Box(
        modifier.then(
            if (cover != null && !overCover) {
                Modifier.background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = arrayOf(0f to cover.top, 0.55f to pageBackground, 1f to pageBackground),
                    ),
                )
            } else if (cover != null) {
                // Hero page: a fixed gradient from the cover's two colours into the page colour. It stays put
                // behind the song list while the cover scrolls away.
                Modifier.background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = arrayOf(0f to cover.top, 0.4f to cover.second, 0.85f to pageBackground, 1f to pageBackground),
                    ),
                )
            } else Modifier,
        ),
    ) {
        Column(Modifier.fillMaxSize().then(if (applyStatusBar && !overCover) Modifier.statusBarsPadding() else Modifier)) {
            if (!overCover) TopBar()
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
                    trailing = editTrailing,
                    query = query,
                    onPlayed = { viewModel.recordPlay(detail) },
                    heroBackdrop = if (overCover) heroCoverUrl else null,
                    heroTint = cover?.top ?: pageBackground,
                    listState = listState,
                    downloadSlot = downloadSlot,
                    rowActions = { index, track ->
                        buildList {
                            add(RowAction("Add to playlist") { addToPlaylist(listOf(track)) })
                            when (detail) {
                                is LibraryDetail.Playlist -> {
                                    if (index > 0) add(RowAction("Move up", Icons.Filled.KeyboardArrowUp) { viewModel.moveInPlaylist(index, -1) })
                                    if (index < tracksState.tracks.lastIndex) {
                                        add(RowAction("Move down", Icons.Filled.KeyboardArrowDown) { viewModel.moveInPlaylist(index, 1) })
                                    }
                                    add(RowAction("Remove from playlist", Icons.Filled.Delete) { viewModel.removeFromPlaylist(index) })
                                }
                                is LibraryDetail.LikedSongs ->
                                    add(RowAction("Remove from Liked songs", Icons.Filled.Delete) { viewModel.removeFromLiked(index) })
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
                            hero = detail !is LibraryDetail.Album,
                            overCover = overCover,
                            caption = if (detail is LibraryDetail.Album || detail is LibraryDetail.Playlist) songCountCaption(tracksState.tracks) else null,
                        )
                    },
                )
            }
        }
        }
        if (overCover) {
            // Over the cover the bar is see-through; scrolled, it fades in from the cover's colour.
            val fadeColor = cover?.top ?: MaterialTheme.colorScheme.background
            val fadeAlpha by androidx.compose.animation.core.animateFloatAsState(if (showScrolledHeader) 1f else 0f, label = "barFade")
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // At the top of the page: a dark fade behind the status icons and the pills.
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.5f * (1f - fadeAlpha)),
                            1f to Color.Transparent,
                        ),
                    )
                    // Scrolled: the cover's colour, solid behind the icons and fading out just below them.
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to fadeColor.copy(alpha = fadeAlpha),
                            0.8f to fadeColor.copy(alpha = fadeAlpha),
                            1f to fadeColor.copy(alpha = 0f),
                        ),
                    ),
            ) {
                Column {
                    if (applyStatusBar) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    TopBar()
                    Spacer(Modifier.height(18.dp))
                }
            }
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
                        onLongClick = { viewModel.showActions(LibraryDetail.Album(album)) },
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
    caption: String? = null,
    /** Playlists get a large centred cover; albums keep the compact side-by-side layout. */
    hero: Boolean = false,
    /** The cover is drawn behind this header (see [HeroBackdrop]): text goes left-aligned and white. */
    overCover: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    if (overCover) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
            if (caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(caption, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        return
    }
    val cc = LocalCoverColors.current
    val titleColor = cc?.onColor ?: MaterialTheme.colorScheme.onSurface
    val subColor = cc?.onVariant ?: MaterialTheme.colorScheme.onSurfaceVariant
    val size = if (hero) 200.dp else 112.dp

    @Composable
    fun Cover() {
        if (heart) {
            Box(
                Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(if (hero) 80.dp else 48.dp),
                )
            }
        } else {
            CoverImage(
                url = coverUrl,
                shape = if (round) CircleShape else RoundedCornerShape(if (hero) 20.dp else 16.dp),
                modifier = Modifier.size(size),
            )
        }
    }

    if (hero) {
        val pageBackground = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxWidth()) {
            // Blurred copy of the cover behind the header, fading into the page (older phones show it unblurred).
            if (!heart && coverUrl != null) {
                coil.compose.AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                        .blur(48.dp)
                        .alpha(0.55f),
                )
                Box(
                    Modifier.matchParentSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to pageBackground,
                        ),
                    ),
                )
            }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cover()
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subColor, textAlign = TextAlign.Center)
            }
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = subColor)
            }
            if (actions != null) {
                Spacer(Modifier.height(10.dp))
                actions()
            }
        }
        }
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Cover()
            if (caption != null) {
                Spacer(Modifier.height(6.dp))
                Text(caption, style = MaterialTheme.typography.labelMedium, color = subColor)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = titleColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subColor)
            }
            if (actions != null) {
                Spacer(Modifier.height(10.dp))
                actions()
            }
        }
    }
}

/**
 * Playlist header backdrop: the cover fills the area behind the title and action row, then fades away so
 * the page's cover-colour gradient shows through behind the song list.
 */
@Composable
private fun HeroBackdrop(coverUrl: String, tint: Color, content: @Composable () -> Unit) {
    val page = MaterialTheme.colorScheme.background
    // The cover fades to transparent, so the page's fixed cover-colour gradient shows through underneath.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The whole cover is shown (a square as wide as the screen), fading out at its bottom edge.
        val square = maxWidth
        Box(Modifier.fillMaxWidth().heightIn(min = square)) {
            val fadeMask = Modifier
                .fillMaxWidth()
                .height(square)
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black, 0.68f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
            val collageUrls = LocalHeroCollage.current
            if (collageUrls != null) {
                StickerCollage(
                    collageUrls,
                    LocalCoverColors.current?.accent?.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary,
                    Modifier.matchParentSize()
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent,
                                ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        },
                )
            } else coil.compose.AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = fadeMask,
            )
            // Keeps white text readable on any cover.
            Box(
                Modifier.fillMaxWidth().height(square).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.40f),
                        0.3f to Color.Black.copy(alpha = 0.08f),
                        0.65f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart)) { content() }
        }
    }
}

/** Pill-shaped search box used inside an album/playlist; focuses itself when it appears. */
@Composable
private fun PlaylistSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search this list", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
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
    trailing: (@Composable (Int) -> Unit)? = null,
    downloadSlot: (@Composable () -> Unit)? = null,
    query: String = "",
    onPlayed: () -> Unit = {},
    /** Cover shown behind the header and the action row, fading out below it (playlists). */
    heroBackdrop: String? = null,
    heroTint: Color = Color.Unspecified,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)?,
) {
    val player = LocalMusicPlayer.current
    val chrome by player.chromeState.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Searching keeps each song's real position, so edit actions still hit the right entry.
    val shown = remember(state.tracks, query) {
        val q = query.trim()
        if (q.isEmpty()) state.tracks.withIndex().toList()
        else state.tracks.withIndex().filter { (_, t) ->
            t.title.contains(q, true) || t.artist.contains(q, true) || (t.album?.contains(q, true) == true)
        }
    }
    val shownTracks = remember(shown) { shown.map { it.value } }

    val actionsRow: @Composable () -> Unit = {
        // Same action row as the original playlist screen: shuffle, play, locate; download on the right.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.FilledTonalIconButton(
                onClick = { onPlayed(); player.playQueue(state.tracks, state.tracks.indices.random(), sourceLabel, startShuffled = true) },
                shape = CircleShape,
                modifier = Modifier.size(50.dp),
            ) { Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(22.dp)) }
            Button(
                onClick = { onPlayed(); player.playQueue(state.tracks, 0, sourceLabel, startShuffled = false) },
                shape = CircleShape,
                modifier = Modifier.height(50.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                    val playing = chrome.current?.internalTrackId
                    val at = shown.indexOfFirst { it.value.internalTrackId == playing }
                    if (playing != null && at >= 0) {
                        scope.launch { listState.animateScrollToItem(at + (if (heroBackdrop != null && header != null) 1 else (if (header != null) 1 else 0) + 1)) }
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size(50.dp),
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = "Locate current song", modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            downloadSlot?.invoke()
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingNavDefaults.contentBottomPadding()),
    ) {
        if (heroBackdrop != null && header != null) {
            item(key = "hero") {
                HeroBackdrop(heroBackdrop, heroTint) {
                    header()
                    if (state.tracks.isNotEmpty()) actionsRow()
                }
            }
        } else {
            if (header != null) item(key = "header") { header() }
            if (state.tracks.isNotEmpty()) item(key = "actions") { actionsRow() }
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
            shown.isEmpty() -> item(key = "no-match") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No songs match \u201c${query.trim()}\u201d", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> itemsIndexed(shown, key = { _, iv -> "${iv.index}_${iv.value.internalTrackId}" }) { pos, iv ->
                JellyTrackRow(
                    iv.value,
                    groupPositionFor(pos, shown.size),
                    number = if (showNumbers) iv.value.trackNumber else null,
                    actions = rowActions(iv.index, iv.value),
                    trailing = if (trailing != null) { @Composable { trailing(iv.index) } } else null,
                ) {
                    onPlayed()
                    player.playQueue(shownTracks, pos, sourceLabel, startShuffled = false)
                }
            }
        }
    }
        // Kept clear of the header at the top and of the mini player / nav bar at the bottom.
        FastScrollbar(
            listState,
            Modifier.align(Alignment.CenterEnd).padding(
                top = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                bottom = FloatingNavDefaults.contentBottomPadding(),
            ),
        )
    }
}

// ---- playlist dialogs ---------------------------------------------------------------------------

@Composable
internal fun NameDialog(
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
internal fun PlaylistPickerDialog(
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
