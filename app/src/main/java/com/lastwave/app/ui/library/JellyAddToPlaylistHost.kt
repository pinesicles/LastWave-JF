package com.lastwave.app.ui.library

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.playback.PlayableTrack

/**
 * App-wide "add to Jellyfin playlist" picker, hosted once in PlayerHost so Feed search, the Now Playing
 * screen and the track menus can all use it (LibraryScreen keeps its own copy for its own screens).
 * [tracks] is what's waiting for a playlist; null means closed.
 */
@Composable
fun JellyAddToPlaylistHost(tracks: List<PlayableTrack>?, onClose: () -> Unit) {
    val viewModel: LibraryViewModel = hiltViewModel(key = "library-global")
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    var createWith by remember { mutableStateOf<List<PlayableTrack>?>(null) }
    LaunchedEffect(tracks) { if (tracks != null) viewModel.refreshPlaylists() }
    if (tracks != null) {
        PlaylistPickerDialog(
            viewModel = viewModel,
            onPick = { viewModel.addToPlaylist(it, tracks); onClose() },
            onNew = { createWith = tracks; onClose() },
            onDismiss = onClose,
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
}
