package com.kolnovel.reader.ui

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Lets the mouse drag the content like a finger on a phone, and keeps it gliding when let go. */
@Composable
fun Modifier.dragScroll(state: ScrollableState, orientation: Orientation = Orientation.Vertical): Modifier {
    val fling = ScrollableDefaults.flingBehavior()
    // Horizontal rows run right-to-left, so dragging right moves forward.
    val sign = if (orientation == Orientation.Horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    return this.draggable(
        rememberDraggableState { delta -> state.dispatchRawDelta(sign * delta) },
        orientation,
        onDragStopped = { velocity -> state.scroll { with(fling) { performFling(sign * velocity) } } },
    )
}

/** The bar on the right edge of the screen that can be grabbed and dragged. */
@Composable
fun BoxScope.EdgeScrollbar(adapter: ScrollbarAdapter) {
    VerticalScrollbar(
        adapter,
        Modifier.align(AbsoluteAlignment.CenterRight).fillMaxHeight().padding(vertical = 6.dp, horizontal = 3.dp),
    )
}

fun scrollbarStyle(colors: AppColors) = ScrollbarStyle(
    minimalHeight = 48.dp,
    thickness = 10.dp,
    shape = RoundedCornerShape(5.dp),
    hoverDurationMillis = 250,
    unhoverColor = colors.text.copy(alpha = 0.28f),
    hoverColor = colors.accent,
)
