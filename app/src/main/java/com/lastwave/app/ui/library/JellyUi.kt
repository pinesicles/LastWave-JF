package com.lastwave.app.ui.library

import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.common.MiniTrayCustomRow
import com.lastwave.app.ui.common.HeaderActionIcon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.JellyfinSession
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.ExpressivePillShape

/** Height of the glass header; scrolling lists add it to their top padding so they pass under it. */
internal val LocalTopInset = compositionLocalOf { 0.dp }

/** One entry in a song's long-press menu. */
internal class RowAction(
    val label: String,
    val icon: ImageVector = Icons.Filled.PlaylistAdd,
    val onClick: () -> Unit,
)

/**
 * A song row in LastWave's own list style (grouped card, 50dp artwork, playing bars,
 * long-press menu), the same look as the Downloads and Local music lists.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JellyTrackRow(
    track: PlayableTrack,
    position: GroupPosition,
    number: Int? = null,
    actions: List<RowAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val player = LocalMusicPlayer.current
    val chrome by player.chromeState.collectAsStateWithLifecycle()
    val isPlaying = chrome.current?.internalTrackId == track.internalTrackId
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.5.dp),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            if (actions.isNotEmpty()) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (number != null) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(24.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.size(50.dp)) {
                    ArtworkImage(
                        name = track.title,
                        artist = track.artist,
                        embeddedUrl = track.artworkUrl,
                        fallbackIcon = Icons.Filled.MusicNote,
                        modifier = Modifier.fillMaxSize().clip(ArtworkShape),
                    )
                    if (isPlaying) {
                        PlayingWaveBars(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(22.dp, 16.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    val album = track.album?.takeIf { it.isNotBlank() && !it.equals(track.title, true) }
                    Text(
                        if (album != null) "${track.artist} • $album" else track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                track.durationMs?.let { ms ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDuration(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                trailing?.invoke()
            }
            if (menu) {
                // Same bottom tray as long-pressing a track on the Stats page.
                TrackMiniTraySheet(
                    data = TrackMiniTrayData(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        artworkUrl = track.artworkUrl,
                        durationMs = track.durationMs,
                    ),
                    customRows = buildList {
                        add(MiniTrayCustomRow(Icons.Filled.PlayArrow, "Play", onClick))
                        add(MiniTrayCustomRow(Icons.Filled.QueuePlayNext, "Play next") { player.playNext(track) })
                        add(MiniTrayCustomRow(Icons.Filled.QueueMusic, "Add to queue") { player.addToQueue(track) })
                        actions.forEach { add(MiniTrayCustomRow(it.icon, it.label, it.onClick)) }
                    },
                    onDismiss = { menu = false },
                )
            }
        }
    }
}

/** LastWave's pill chip strip (as on the original Feed), without the "All" chip. */
@Composable
internal fun JellyChipsBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(labels.size) { index ->
                val isSelected = index == selected
                val bg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    label = "chipBg",
                )
                val fg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "chipFg",
                )
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(index)
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = bg,
                    modifier = Modifier.height(38.dp),
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            labels[index],
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun JellySearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        shape = ExpressivePillShape,
        placeholder = { Text("Search") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
    )
}

/** Results for the search box: the server (Feed) or your saved music (Library). */
@Composable
internal fun JellySearchResults(session: JellyfinSession?, state: SearchState, viewModel: LibraryViewModel) {
    if (session == null) {
        SignedOutNotice()
        return
    }
    val player = LocalMusicPlayer.current
    val addToPlaylist = LocalJellyAddToPlaylist.current
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val hits = state.hits
    val empty = hits.albums.isEmpty() && hits.artists.isEmpty() && hits.playlists.isEmpty() && hits.tracks.isEmpty()
    val message: String? = when {
        state.query.isBlank() ->
            if (mode == LibraryMode.SAVED) "Search your saved albums, artists, playlists and liked songs" else "Search your Jellyfin server"
        state.error != null || (state.loading && empty) -> null
        empty -> "Nothing found"
        else -> null
    }
    when {
        message != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            ErrorWithRetry(state.error) { viewModel.setSearchQuery(state.query) }
        }
        state.loading && empty -> CenteredProgress()
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = FloatingNavDefaults.contentBottomPadding()),
        ) {
            if (hits.tracks.isNotEmpty()) {
                item(key = "h_songs") { SearchLabel("Songs") }
                itemsIndexed(hits.tracks, key = { i, t -> "s_${i}_${t.internalTrackId}" }) { index, track ->
                    JellyTrackRow(
                        track,
                        groupPositionFor(index, hits.tracks.size),
                        actions = listOf(RowAction("Add to playlist") { addToPlaylist(listOf(track)) }),
                    ) { player.playQueue(hits.tracks, index, "Search", startShuffled = false) }
                }
            }
            if (hits.albums.isNotEmpty()) {
                item(key = "h_albums") { SearchLabel("Albums") }
                item(key = "albums") { Shelf(session, hits.albums, false, viewModel) { viewModel.open(LibraryDetail.Album(it)) } }
            }
            if (hits.artists.isNotEmpty()) {
                item(key = "h_artists") { SearchLabel("Artists") }
                item(key = "artists") { Shelf(session, hits.artists, true, viewModel) { viewModel.open(LibraryDetail.Artist(it)) } }
            }
            if (hits.playlists.isNotEmpty()) {
                item(key = "h_playlists") { SearchLabel("Playlists") }
                item(key = "playlists") { Shelf(session, hits.playlists, false, viewModel) { viewModel.open(LibraryDetail.Playlist(it)) } }
            }
        }
    }
}

@Composable
private fun SearchLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Shelf(
    session: JellyfinSession,
    items: List<JfItem>,
    round: Boolean,
    viewModel: LibraryViewModel,
    onClick: (JfItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            CoverCard(
                title = item.name.orEmpty(),
                subtitle = item.albumArtist,
                coverUrl = viewModel.coverUrl(session, item),
                round = round,
                onClick = { onClick(item) },
                modifier = Modifier.width(140.dp),
            )
        }
    }
}

/** Save (heart) and Download buttons shown under an album/playlist title. */
@Composable
internal fun DetailActionButtons(
    favourite: Boolean,
    onFavourite: () -> Unit,
    downloaded: Boolean,
    /** 0..1 while downloading, null when idle or finished. */
    downloadFraction: Float?,
    onDownload: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderActionIcon(
            if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (favourite) "Remove from saved" else "Save",
            onFavourite,
        )
        if (downloadFraction != null) {
            FilledTonalIconButton(onClick = onDownload) {
                CircularProgressIndicator(progress = { downloadFraction }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            HeaderActionIcon(
                if (downloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                if (downloaded) "Downloaded" else "Download",
                onDownload,
            )
        }
    }
}
