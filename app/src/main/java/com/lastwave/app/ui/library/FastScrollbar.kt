package com.lastwave.app.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A scrollbar thumb on the right edge that shows while the list is scrolling and can be dragged to jump through it fast.
 * Place it in a Box on top of the LazyColumn that uses [state].
 */
@Composable
internal fun FastScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1200); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "scrollbar")
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total <= visible + 3) return
    BoxWithConstraints(modifier.fillMaxHeight().width(28.dp)) {
        val trackPx = with(LocalDensity.current) { maxHeight.toPx() }
        val thumbPx = with(LocalDensity.current) { 64.dp.toPx() }
        val range = (total - visible).coerceAtLeast(1)
        val first = state.firstVisibleItemIndex
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull()
        val within = if (info != null && info.size > 0) (-info.offset / info.size.toFloat()).coerceIn(0f, 1f) else 0f
        val fraction = ((first + within) / range).coerceIn(0f, 1f)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * (trackPx - thumbPx)).toInt()) }
                .padding(end = 4.dp)
                .alpha(alpha)
                .size(width = 8.dp, height = 64.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        )
        if (shown) Box(
            Modifier.matchParentSizeCompat().pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val f = ((change.position.y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
                    scope.launch { state.scrollToItem((f * range).toInt().coerceIn(0, total - 1)) }
                }
            },
        )
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxHeight().width(28.dp))
