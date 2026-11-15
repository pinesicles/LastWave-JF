package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jellyfin.download.JellyDownloads
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TrackDownloadsViewModel @Inject constructor(downloads: JellyDownloads) : ViewModel() {
    val states = downloads.trackStates
}

internal val LocalTrackDownloads = compositionLocalOf<State<Map<String, JellyDownloads.TrackDlState>>?> { null }

/** Makes per-song download state available to every [JellyTrackRow] inside [content]. */
@Composable
fun ProvideTrackDownloads(content: @Composable () -> Unit) {
    val vm: TrackDownloadsViewModel = hiltViewModel()
    val states = vm.states.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalTrackDownloads provides states, content = content)
}

/** Downloaded tick, error mark, or a small progress ring with a percentage. Nothing if not downloaded. */
@Composable
internal fun TrackDownloadIndicator(itemId: String?) {
    val states = LocalTrackDownloads.current?.value ?: return
    val s = states[itemId ?: return] ?: return
    when {
        s.done -> Icon(
            Icons.Filled.DownloadDone,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        s.failed -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Download failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.fraction > 0f) {
                CircularProgressIndicator(progress = { s.fraction }, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text(
                    "${(s.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp).padding0(),
                )
            } else {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun Modifier.padding0() = this.then(Modifier.padding(start = 3.dp))
