package com.lastwave.app.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.download.COLLECTION_ALBUM
import com.lastwave.app.data.jellyfin.download.COLLECTION_PLAYLIST
import com.lastwave.app.ui.common.MiniTrayCustomRow
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.player.LocalMusicPlayer
import kotlinx.coroutines.launch

/**
 * The long-press sheet for albums, playlists and artists. It reuses the same tray as long-pressing a
 * track, so it looks and behaves the same. Open it with [LibraryViewModel.showActions].
 */
@Composable
internal fun CollectionActionsHost(viewModel: LibraryViewModel, canAddToPlaylist: Boolean) {
    val detail by viewModel.actions.collectAsStateWithLifecycle()
    val d = detail ?: return
    val session = (viewModel.sessionState.collectAsStateWithLifecycle().value as? LibrarySession.SignedIn)?.session
    val favourites by viewModel.favouriteOverrides.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedCollections.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val addToPlaylist = LocalJellyAddToPlaylist.current
    val player = LocalMusicPlayer.current
    val scope = rememberCoroutineScope()
    val item = d.item

    val isFavourite = favourites[item.id] ?: item.isFavorite
    val isDownloaded = downloaded.any { it.id == item.id }
    val isPinned = item.id in pins
    val isArtist = d is LibraryDetail.Artist
    val isAlbum = d is LibraryDetail.Album
    val isPlaylist = d is LibraryDetail.Playlist
    val type = if (isAlbum) COLLECTION_ALBUM else COLLECTION_PLAYLIST

    fun withTracks(block: (List<com.lastwave.app.playback.PlayableTrack>) -> Unit) {
        scope.launch {
            val tracks = runCatching { viewModel.tracksFor(d) }.getOrNull().orEmpty()
            if (tracks.isNotEmpty()) block(tracks)
        }
    }

    val rows = buildList {
        add(MiniTrayCustomRow(Icons.Filled.PlayArrow, "Play") {
            viewModel.recordPlay(d)
            withTracks { player.playQueue(it, 0, item.name.orEmpty(), startShuffled = false) }
        })
        add(MiniTrayCustomRow(Icons.Filled.Shuffle, "Shuffle") {
            viewModel.recordPlay(d)
            withTracks { player.playQueue(it, 0, item.name.orEmpty(), startShuffled = true) }
        })
        add(MiniTrayCustomRow(Icons.Filled.QueueMusic, "Add to queue") {
            withTracks { tracks -> tracks.forEach(player::addToQueue) }
        })
        if (!isArtist) {
            add(MiniTrayCustomRow(if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (isFavourite) "Unsave" else "Save") {
                viewModel.toggleFavourite(item)
            })
            if (isDownloaded) {
                add(MiniTrayCustomRow(Icons.Filled.Delete, "Remove download") { viewModel.removeDownload(item.id) })
            } else {
                add(MiniTrayCustomRow(Icons.Filled.Download, "Download") { viewModel.downloadCollection(item, type) })
            }
        } else {
            add(MiniTrayCustomRow(if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (isFavourite) "Unsave" else "Save") {
                viewModel.toggleFavourite(item)
            })
        }
        if (isAlbum || isArtist || isPlaylist) {
            add(MiniTrayCustomRow(Icons.Filled.PushPin, if (isPinned) "Unpin" else "Pin to top") { viewModel.togglePin(item.id) })
        }
        if (canAddToPlaylist) {
            add(MiniTrayCustomRow(Icons.Filled.PlaylistAdd, "Add to playlist") { withTracks { addToPlaylist(it) } })
        }
        if (isAlbum && !item.albumArtist.isNullOrBlank()) {
            add(MiniTrayCustomRow(Icons.Filled.Person, "Go to artist") { viewModel.openArtistOf(item) })
        }
    }

    TrackMiniTraySheet(
        data = TrackMiniTrayData(
            title = item.name.orEmpty(),
            artist = when (d) {
                is LibraryDetail.Artist -> "Artist"
                is LibraryDetail.Playlist -> "Playlist"
                else -> item.albumArtist.orEmpty()
            },
            artworkUrl = session?.let { viewModel.coverUrl(it, item) },
        ),
        onDismiss = viewModel::dismissActions,
        customRows = rows,
    )
}
