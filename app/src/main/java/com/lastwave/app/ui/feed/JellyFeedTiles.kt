package com.lastwave.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.library.CoverImage
import com.lastwave.app.ui.theme.ExpressivePillShape

class FeedTile(
    val title: String,
    val coverUrl: String?,
    val heart: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/** Two tiles per row (like Spotify): your Liked songs plus what you play most and most recently. */
@Composable
internal fun FeedTilesGrid(tiles: List<FeedTile>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { FeedTileCard(it, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedTileCard(tile: FeedTile, modifier: Modifier) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = tile.onClick, onLongClick = tile.onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.heart) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
        } else {
            CoverImage(tile.coverUrl, RoundedCornerShape(0.dp), Modifier.size(56.dp))
        }
        Text(
            tile.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
    }
}

/** "Shuffle all", now in the Library half of the Feed. [topInset] clears the glass header above. */
@Composable
internal fun ShuffleAllBar(topInset: Dp, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topInset + 4.dp, bottom = 4.dp)) {
        Button(onClick = onClick, shape = ExpressivePillShape, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Shuffle all")
        }
    }
}
