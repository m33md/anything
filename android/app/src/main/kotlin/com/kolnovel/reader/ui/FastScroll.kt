package com.kolnovel.reader.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A handle on the right edge of a list: drag it (or tap the edge) to jump anywhere,
 * e.g. from chapter 1 to chapter 3000 in one move. Faint while idle, bright while in use.
 */
@Composable
private fun BoxScope.FastScrollbar(
    show: Boolean,
    scrolling: Boolean,
    progress: () -> Float,
    jumpTo: suspend (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!show) return
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val active = dragging || scrolling
    val alpha by animateFloatAsState(if (active) 1f else 0.45f, label = "scrollbar")
    val thumbWidth by animateDpAsState(if (dragging) 9.dp else 5.dp, label = "thumb")
    val thumbHeight = 52.dp
    BoxWithConstraints(
        modifier.align(AbsoluteAlignment.TopRight).fillMaxHeight().width(26.dp).padding(vertical = 6.dp),
    ) {
        val density = LocalDensity.current
        val track = constraints.maxHeight.toFloat()
        val thumb = with(density) { thumbHeight.toPx() }
        fun jump(y: Float) {
            val f = ((y - thumb / 2) / (track - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
            scope.launch { jumpTo(f) }
        }
        Box(
            Modifier.matchParentSize()
                .pointerInput(track) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jump(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    }
                }
                .pointerInput(track) { detectTapGestures { jump(it.y) } },
        )
        val y = (progress().coerceIn(0f, 1f) * (track - thumb)).roundToInt()
        Box(
            Modifier.align(AbsoluteAlignment.TopRight)
                .offset { IntOffset(0, y) }
                .padding(end = 3.dp)
                .alpha(alpha)
                .size(width = thumbWidth, height = thumbHeight)
                .clip(CircleShape)
                .background(colors.accent),
        )
    }
}

@Composable
fun BoxScope.FastScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val show by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount > info.visibleItemsInfo.size + 4
        }
    }
    FastScrollbar(
        show = show,
        scrolling = state.isScrollInProgress,
        progress = {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val scrollable = (info.totalItemsCount - visible.size).coerceAtLeast(1)
            val firstSize = visible.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
            (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / firstSize) / scrollable
        },
        jumpTo = { f ->
            val info = state.layoutInfo
            val scrollable = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
            state.scrollToItem((f * scrollable).roundToInt())
        },
        modifier = modifier,
    )
}

@Composable
fun BoxScope.FastScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val show by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount > info.visibleItemsInfo.size * 2
        }
    }
    FastScrollbar(
        show = show,
        scrolling = state.isScrollInProgress,
        progress = {
            val info = state.layoutInfo
            val scrollable = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.firstVisibleItemIndex.toFloat() / scrollable
        },
        jumpTo = { f ->
            val info = state.layoutInfo
            val scrollable = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
            state.scrollToItem((f * scrollable).roundToInt())
        },
        modifier = modifier,
    )
}

@Composable
fun BoxScope.FastScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    FastScrollbar(
        show = state.maxValue > 0,
        scrolling = state.isScrollInProgress,
        progress = { if (state.maxValue <= 0) 0f else state.value.toFloat() / state.maxValue },
        jumpTo = { f -> state.scrollTo((f * state.maxValue).roundToInt()) },
        modifier = modifier,
    )
}
