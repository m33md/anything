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

/** The library page's filter; the Library section's strip opens it on one. */
enum class LibraryTab(val label: String, val title: String) {
    All("All", "Your Library"),
    Playlists("Playlists", "Playlists"),
    Albums("Albums", "Albums"),
    Artists("Artists", "Artists"),
}

/** The three places the top bar leads to, each with its own sections under it. */
enum class Section(val label: String, val icon: String, val root: Screen) {
    Home("Home", "sf_house_fill", Screen.Home),
    Explore("Explore", "sf_square_grid_fill", Screen.Explore),
    Library("Library", "sf_music_note_square_stack", Screen.Library),
}

/** Which section a screen is one of the pages of; detail pages belong to none. */
fun Screen.section(): Section? = when (this) {
    Screen.Home -> Section.Home
    Screen.Explore -> Section.Explore
    Screen.Library, Screen.Liked, Screen.LocalFiles, Screen.Downloads, Screen.History, Screen.Stats, Screen.MyTop, Screen.Cached -> Section.Library
    else -> null
}

/** Places on the Explore page its strip can jump to. */
enum class ExploreAnchor(val label: String, val icon: String) {
    NewReleases("New Releases", "album"),
    Charts("Charts", "trending_up"),
    Moods("Moods & Genres", "grid_view"),
}

/** What the full-screen player shows beside the artwork. */
enum class FullPlayerMode { Lyrics, Queue, Artwork }

/** Window-wide UI state that isn't navigation: overlays, panels, the top bar's tint. */
object UiState {
    var fullPlayer by mutableStateOf(false)
    var fullPlayerMode by mutableStateOf(FullPlayerMode.Lyrics)

    /** Lyrics across the whole player, the phone's full-screen lyrics; only while the lyrics are open. */
    var lyricsFullscreen by mutableStateOf(false)

    /**
     * The lyrics button: opens the lyrics, or with one-tap on opens them full screen; pressed with
     * them open, it goes back to the cover.
     */
    fun toggleLyrics(oneTap: Boolean) {
        if (fullPlayerMode == FullPlayerMode.Lyrics) {
            fullPlayerMode = FullPlayerMode.Artwork
            lyricsFullscreen = false
        } else {
            fullPlayerMode = FullPlayerMode.Lyrics
            lyricsFullscreen = oneTap
        }
    }

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

    /** Opens on the phone's default library chip, from Controls & layout. */
    var libraryTab by mutableStateOf(LibraryTab.entries.firstOrNull { it.name == AppSettings.value.defaultLibraryTab } ?: LibraryTab.All)

    /**
     * The section whose strip is unfolded under the top bar, if any. Clicking a section's button
     * unfolds it; leaving that section's pages folds it back into the button.
     */
    var openSection by mutableStateOf<Section?>(null)

    /** Home's mood chips, which live in its strip: the titles the feed offers and the one picked. */
    var homeChips by mutableStateOf<List<String>>(emptyList())
    var homeChip by mutableStateOf<String?>(null)

    /** Explore's strip asks the page to scroll to an anchor; the page reports the one in view. */
    var exploreJump by mutableStateOf<ExploreAnchor?>(null)
    var exploreInView by mutableStateOf<ExploreAnchor?>(null)

    /** The anchors Explore has content for; null until it has loaded, when all are offered. */
    var exploreAnchors by mutableStateOf<Set<ExploreAnchor>?>(null)

    /** The top bar's buttons: go to the section, or fold and unfold its strip when already there. */
    fun openSection(section: Section, navigator: Navigator) {
        val here = navigator.current.screen.section()
        when {
            here != section -> {
                navigator.openRoot(section.root)
                openSection = section
            }
            openSection == section -> openSection = null
            else -> openSection = section
        }
    }

    fun toggleSideBar() {
        AppSettings.update { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }
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

/** The top bar, which is also the window's title bar. */
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
