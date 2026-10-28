/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings

/** Which side panel is open next to the content, if any. */
enum class SidePanel(val key: String) {
    NowPlaying("nowPlaying"),
    Queue("queue"),
    Lyrics("lyrics"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** What the full-screen player shows beside the artwork. */
enum class FullPlayerMode { Lyrics, Queue, Artwork }

/** Window-wide UI state that isn't navigation: overlays, panels, the top bar's tint. */
object UiState {
    var fullPlayer by mutableStateOf(false)
    var fullPlayerMode by mutableStateOf(FullPlayerMode.Lyrics)

    /** Focus target for the top bar's search field (Ctrl+L / Ctrl+K). */
    val searchFocus = FocusRequester()

    /**
     * The text field that has the keyboard, if any. Typing a space there must not also
     * reach the window's Space-to-play shortcut: the field takes the typed character,
     * but the key press itself still bubbles up to the window.
     */
    var typingIn by mutableStateOf<Any?>(null)
        private set

    fun typingFocusChanged(field: Any, focused: Boolean) {
        if (focused) typingIn = field else if (typingIn == field) typingIn = null
    }

    /** 0 = top bar transparent over a page's hero, 1 = solid. */
    var topBarOpacity by mutableFloatStateOf(0f)

    /** The colour the top bar turns solid into, usually the page's hero colour. */
    var topBarTint by mutableStateOf(Color(0xFF121212))

    fun toggleSidePanel(panel: SidePanel) {
        AppSettings.update { it.copy(rightPanel = if (it.rightPanel == panel.key) null else panel.key) }
    }

    fun openSidePanel(panel: SidePanel) {
        AppSettings.update { it.copy(rightPanel = panel.key) }
    }

    fun closeSidePanel() {
        AppSettings.update { it.copy(rightPanel = null) }
    }
}

val TopBarHeight = 64.dp

/** Fades the top bar in as [state] scrolls past the hero, tinted with [tint]. */
@Composable
fun TrackTopBar(state: LazyListState, tint: Color, fadeDistancePx: Float = 260f) {
    LaunchedEffect(state, tint) {
        UiState.topBarTint = tint
        snapshotFlow {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / fadeDistancePx).coerceIn(0f, 1f)
        }.collect { UiState.topBarOpacity = it }
    }
}

@Composable
fun TrackTopBar(state: LazyGridState, tint: Color, fadeDistancePx: Float = 260f) {
    LaunchedEffect(state, tint) {
        UiState.topBarTint = tint
        snapshotFlow {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / fadeDistancePx).coerceIn(0f, 1f)
        }.collect { UiState.topBarOpacity = it }
    }
}
