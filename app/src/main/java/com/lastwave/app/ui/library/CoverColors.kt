package com.lastwave.app.ui.library

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours taken from a cover: a background tint and readable text colours on top of it. */
@Immutable
class CoverColors(val top: Color, val second: Color, val onColor: Color, val onVariant: Color, val accent: Color = Color.Unspecified)

internal val LocalCoverColors = compositionLocalOf<CoverColors?> { null }

private val baseCache = LruCache<String, Pair<Int, Int>>(64)

/**
 * Reads the main colour of the cover at [url] (null until it loads) and turns it into a tint that suits
 * the current light/dark theme, plus text colours with enough contrast on it.
 */
@Composable
internal fun rememberCoverColors(url: String?): CoverColors? {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    var base by remember(url) { mutableStateOf<Pair<Int, Int>?>(url?.let { baseCache.get(it) }) }

    LaunchedEffect(url) {
        if (url == null || base != null) return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null
                val p = Palette.from(bitmap).clearFilters().generate()
                val first = (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb ?: return@runCatching null
                // A second, different colour from the same cover for the page gradient.
                val firstHsl = FloatArray(3).also { ColorUtils.colorToHSL(first, it) }
                val second = listOfNotNull(
                    p.darkVibrantSwatch, p.mutedSwatch, p.lightVibrantSwatch, p.darkMutedSwatch, p.dominantSwatch,
                ).map { it.rgb }.firstOrNull { c ->
                    val h = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }
                    val d = kotlin.math.abs(h[0] - firstHsl[0]).let { if (it > 180f) 360f - it else it }
                    d > 25f
                } ?: first
                first to second
            }.getOrNull()
        }
        if (found != null) {
            baseCache.put(url, found)
            base = found
        }
    }

    val (rgb, rgb2) = base ?: return null
    fun tint(c: Int): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        // Keep the hue, tone down the rest so it reads as a tint rather than a flood of colour.
        hsl[1] = hsl[1].coerceAtMost(0.7f)
        hsl[2] = if (dark) hsl[2].coerceIn(0.14f, 0.30f) else hsl[2].coerceIn(0.78f, 0.90f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
    val top by animateColorAsState(tint(rgb), tween(500), label = "coverTop")
    val second by animateColorAsState(tint(rgb2), tween(500), label = "coverSecond")
    val on = if (top.luminance() > 0.5f) Color(0xFF16161A) else Color.White
    val accent = run {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.6f)
        hsl[2] = hsl[2].coerceIn(0.45f, 0.62f)
        Color(ColorUtils.HSLToColor(hsl))
    }
    return CoverColors(top, second, on, on.copy(alpha = 0.72f), accent)
}
