package com.lastwave.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack

/** "12 songs · 48 min" under the cover of an album or playlist. */
internal fun songCountCaption(tracks: List<PlayableTrack>): String? {
    if (tracks.isEmpty()) return null
    val minutes = tracks.sumOf { it.durationMs ?: 0L } / 60_000
    val songs = if (tracks.size == 1) "1 song" else "${tracks.size} songs"
    return if (minutes > 0) "$songs · ${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"}" else songs
}

/**
 * Download control for an album/playlist, shown at the right end of the action row.
 * Not downloaded: a download button. Downloading: "done / total" text and a ring with the percentage.
 * Finished: just a tick (the text goes away). Tap to download, retry failed songs, or remove.
 */
@Composable
internal fun CollectionDownloadStatus(
    itemId: String,
    downloaded: Boolean,
    done: Int,
    total: Int,
    failed: Int,
    viewModel: LibraryViewModel,
    onClick: () -> Unit,
) {
    val complete = downloaded && total > 0 && done >= total
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (downloaded && !complete) {
            Text(
                "$done / $total" + if (failed > 0) " · $failed failed" else "",
                style = MaterialTheme.typography.labelLarge,
                color = if (failed > 0) MaterialTheme.colorScheme.error
                else LocalCoverColors.current?.onVariant ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloaded && !complete -> {
                val fraction by viewModel.downloadFraction(itemId).collectAsStateWithLifecycle(0f)
                FilledTonalIconButton(onClick = onClick, shape = CircleShape, modifier = Modifier.size(50.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.size(44.dp),
                            strokeWidth = 3.dp,
                        )
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            complete -> FilledTonalIconButton(
                onClick = onClick,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.size(50.dp),
            ) { Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded", modifier = Modifier.size(22.dp)) }
            else -> FilledTonalIconButton(onClick = onClick, shape = CircleShape, modifier = Modifier.size(50.dp)) {
                Icon(Icons.Filled.Download, contentDescription = "Download", modifier = Modifier.size(22.dp))
            }
        }
    }
}
