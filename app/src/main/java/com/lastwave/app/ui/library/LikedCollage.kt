package com.lastwave.app.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Cover URLs for the Liked songs hero, or null on every other page. Read by the hero backdrop. */
internal val LocalHeroCollage = compositionLocalOf<List<String>?> { null }

/**
 * A "sticker bomb" of album covers: tilted, overlapping, white-edged squares scattered over a square the width of the
 * screen, with a heart in [heart] colour on top. Positions come from the order of [urls], so it stays put while you look at it.
 */
@Composable
internal fun StickerCollage(urls: List<String>, heart: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        // A jittered 3-column grid sized to the whole area, so the covers fill the hero edge to edge.
        val stickers = remember(urls, w, h) {
            val rnd = Random(urls.hashCode())
            val cols = 3
            val cell = w / cols
            val rows = kotlin.math.ceil((h / cell).toDouble()).toInt().coerceAtLeast(1) + 1
            List(cols * rows) { i ->
                val c = i % cols
                val r = i / cols
                val size = 0.46f + rnd.nextFloat() * 0.14f
                Sticker(
                    url = urls[i % urls.size], size = size,
                    x = (c / cols.toFloat()) - 0.06f + (rnd.nextFloat() - 0.5f) * 0.1f,
                    y = (r * cell.value / w.value) - 0.1f + (rnd.nextFloat() - 0.5f) * 0.1f,
                    tilt = (rnd.nextFloat() - 0.5f) * 30f,
                )
            }
        }
        stickers.forEach { s ->
            Box(
                Modifier
                    .offset(w * s.x, w * s.y)
                    .size(w * s.size)
                    .rotate(s.tilt)
                    .shadow(6.dp, RoundedCornerShape(6.dp))
                    .border(BorderStroke(3.dp, Color.White), RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                coil.compose.AsyncImage(
                    model = s.url, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(w * s.size),
                )
            }
        }
        // The heart: a white sticker edge (the same icon, larger) under the coloured one.
        Box(Modifier.align(Alignment.CenterStart).offset(w * 0.08f, -(w * 0.12f)).rotate(-8f), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(w * 0.46f))
            Icon(Icons.Filled.Favorite, null, tint = heart, modifier = Modifier.size(w * 0.38f))
        }
    }
}

private class Sticker(val url: String, val size: Float, val x: Float, val y: Float, val tilt: Float)
