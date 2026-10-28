/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.Library
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.InteractiveHighlight
import com.convx.desktop.ui.glass.glassPuck
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.theme.ConvxFonts
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/*
 * The phone's tab view side bar (ui/component/FloatingSideBar.kt): one floating panel of
 * glass with a header, the primary tabs on a springing glass puck, the library sections,
 * and a pinned footer. Sizes are the phone's.
 */

/** Width of the whole panel, and of the icon-only rail it collapses to. */
val SideBarWidth: Dp = 320.dp
val SideBarCollapsedWidth: Dp = 72.dp

/** Gap between the window edge and the panel; on the phone it is 30dp of a tablet's width. */
val SideBarMargin: Dp = 12.dp

/** Where rows start beside the panel; lists still run under it. */
val SideBarContentInset: Dp = SideBarMargin + SideBarWidth + 8.dp
val SideBarCollapsedContentInset: Dp = SideBarMargin + SideBarCollapsedWidth + 8.dp

private val SideTabHeight = 44.dp
private val SideRowLeadingSlot = 24.dp
private val SideRowIconSize = 22.dp
private const val PuckRestShadowAlpha = 0.35f

/** The phone side bar's Apple Music puck: #333 at 80%. */
private val PuckWash = Color(0xFF333333).copy(alpha = 0.80f)

/** A primary destination: one row on the puck. */
@Immutable
data class SideTab(val label: String, val icon: String, val screen: Screen)

val SideTabs = listOf(
    SideTab("Search", "search", Screen.Search),
    SideTab("Home", "sf_house_fill", Screen.Home),
    SideTab("Explore", "sf_square_grid_fill", Screen.Explore),
    SideTab("Library", "sf_music_note_square_stack", Screen.Library),
)

/** Which tab a screen belongs to; detail pages keep the tab they were opened from lit. */
fun Screen.tab(): Screen? = when (this) {
    Screen.Search, is Screen.SearchResults -> Screen.Search
    Screen.Home -> Screen.Home
    Screen.Explore -> Screen.Explore
    Screen.Library -> Screen.Library
    else -> null
}

/** A row below the tabs: a library view, a playlist, history. */
@Immutable
private data class SideLink(
    val label: String,
    val icon: String? = null,
    val art: (@Composable () -> Unit)? = null,
    val screen: Screen? = null,
    val onClick: () -> Unit,
    val menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
)

@Immutable
private data class SideSection(val title: String?, val links: List<SideLink>)

@Composable
fun FloatingSideBar(collapsed: Boolean, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val panelWidth by animateDpAsState(if (collapsed) SideBarCollapsedWidth else SideBarWidth, spring(0.9f, 400f))
    // Measured against the whole panel, so a small radius keeps the glow to about one row.
    val glow = remember(scope) { InteractiveHighlight(scope, radiusScale = 0.3f) }
    // A capsule when collapsed; the panel's own corner would look square at rail width.
    val shape = if (collapsed) GlassShapes.Capsule else GlassShapes.Panel
    val chrome = Color.White
    val sections = rememberSideSections()

    Column(
        modifier
            .width(panelWidth)
            .fillMaxHeight()
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, GlassConfig.SidePanel, highlightAlpha = 0.3f)
            .then(glow.modifier)
            .then(glow.gestureModifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(start = if (collapsed) 0.dp else 16.dp, end = if (collapsed) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            if (!collapsed) {
                Text(
                    "Convx",
                    style = ConvxType.Headline.copy(fontFamily = ConvxFonts.Chillax, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = 0.sp),
                    color = chrome,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Tooltip(if (collapsed) "Expand side bar" else "Collapse side bar") {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .pressable({ UiState.toggleSideBar() }, pressScale = 0.9f),
                    contentAlignment = Alignment.Center,
                ) {
                    ConvxIcon("sidebar", tint = chrome, size = 20.dp)
                }
            }
        }

        SideNavTabs(
            current = navigator.current.screen,
            collapsed = collapsed,
            onClick = { tab ->
                navigator.openRoot(tab.screen)
                if (tab.screen == Screen.Search) runCatching { UiState.searchFocus.requestFocus() }
            },
            modifier = Modifier.padding(horizontal = 10.dp),
        )

        Column(
            Modifier
                .weight(1f)
                // Rows dissolve into the tabs and the footer instead of meeting a hard edge.
                .fadingEdge(18.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // The library needs labels to mean anything; the rail keeps only the tabs.
            if (!collapsed) {
                sections.forEach { section ->
                    section.title?.let { title ->
                        Text(
                            title,
                            style = ConvxType.Headline,
                            color = chrome.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 14.dp),
                        )
                    }
                    section.links.forEach { link ->
                        SideLinkRow(link, selected = link.screen != null && link.screen == navigator.current.screen)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        SettingsRow(collapsed, selected = navigator.current.screen == Screen.Settings) { navigator.navigate(Screen.Settings) }
    }
}

/**
 * The phone nav bar stood on its end: a plain glass puck springs to the selected row and
 * nothing else moves it. It draws under the icons, so each row shows one icon.
 */
@Composable
private fun SideNavTabs(
    current: Screen,
    collapsed: Boolean,
    onClick: (SideTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    // Detail pages keep the last tab lit, as the phone's sticky selection does.
    val selectedTab = remember { mutableStickyTab() }
    current.tab()?.let { selectedTab.value = it }
    val selectedIndex = SideTabs.indexOfFirst { it.screen == selectedTab.value }.coerceAtLeast(0)
    val density = LocalDensity.current
    val rowPx = with(density) { SideTabHeight.toPx() }
    val puck = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        // The phone bar's underdamped spring.
        puck.animateTo(selectedIndex.toFloat(), spring(0.6f, 380f, 0.001f))
    }
    val padding = PaddingValues(horizontal = 6.dp)

    Box(modifier.fillMaxWidth().height(SideTabHeight * SideTabs.size)) {
        Box(
            Modifier
                .padding(padding)
                .graphicsLayer { translationY = puck.value * rowPx }
                .fillMaxWidth()
                .height(SideTabHeight)
                .glassPuck(GlassShapes.Capsule, blurRadius = GlassConfig.SidePanel.blurRadius.dp, wash = PuckWash, shadowAlpha = PuckRestShadowAlpha),
        )
        Column(Modifier.fillMaxSize().padding(padding)) {
            SideTabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(SideTabHeight)
                        .clip(GlassShapes.Capsule)
                        .background(if (hovered && !selected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                        .hoverable(interaction)
                        .pressable({ onClick(tab) }, interaction = interaction, pressScale = 0.97f)
                        .padding(horizontal = if (collapsed) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                ) {
                    Box(Modifier.size(SideRowLeadingSlot), contentAlignment = Alignment.Center) {
                        ConvxIcon(tab.icon, tint = accent, size = SideRowIconSize, contentDescription = tab.label)
                    }
                    if (!collapsed) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tab.label,
                            style = ConvxType.Headline.copy(fontWeight = FontWeight.Normal),
                            color = if (selected) accent else GlassTokens.InactiveTab,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private class StickyTab(var value: Screen = Screen.Home)

private fun mutableStickyTab() = StickyTab()

@Composable
private fun SideLinkRow(link: SideLink, selected: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val anchor = com.convx.desktop.ui.components.rememberMenuAnchor()
    Box(if (link.menu != null) Modifier.onSecondaryClick(anchor.openAtProvider()) else Modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SideTabHeight)
                .clip(GlassShapes.Capsule)
                .background(
                    when {
                        selected -> Color.White.copy(alpha = 0.12f)
                        hovered -> Color.White.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .pressable(link.onClick, interaction = interaction, pressScale = 0.98f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(SideRowLeadingSlot), contentAlignment = Alignment.Center) {
                when {
                    link.art != null -> link.art.invoke()
                    link.icon != null -> ConvxIcon(link.icon, tint = LocalAccent.current, size = SideRowIconSize)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                link.label,
                style = ConvxType.Headline.copy(fontWeight = FontWeight.Normal),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        link.menu?.let { menu -> com.convx.desktop.ui.components.AppMenu(anchor) { menu { anchor.expanded = false } } }
    }
}

/** The pinned bottom row: the phone shows the account there, or a settings cog without one. */
@Composable
private fun SettingsRow(collapsed: Boolean, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(GlassShapes.Capsule)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.98f)
            .padding(horizontal = if (collapsed) 20.dp else 25.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (selected || hovered) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon("settings", tint = Color.White, size = 18.dp)
        }
        if (!collapsed) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings",
                style = ConvxType.SubheadStrong,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The phone's sections: the library's views, your playlists, then history and stats. */
@Composable
private fun rememberSideSections(): List<SideSection> {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    return remember(library.localPlaylists, navigator) {
        fun openLibrary(tab: LibraryTab) {
            UiState.libraryTab = tab
            navigator.openRoot(Screen.Library)
        }
        listOf(
            SideSection(
                "Library",
                listOf(
                    SideLink("Recently Added", "music_history", onClick = { openLibrary(LibraryTab.All) }),
                    SideLink("Artists", "artist", onClick = { openLibrary(LibraryTab.Artists) }),
                    SideLink("Albums", "album", onClick = { openLibrary(LibraryTab.Albums) }),
                    SideLink("Songs", "music_note", screen = Screen.Liked, onClick = { navigator.navigate(Screen.Liked) }),
                ),
            ),
            SideSection(
                "Playlists",
                buildList {
                    add(SideLink("All Playlists", "playlist_play", onClick = { openLibrary(LibraryTab.Playlists) }))
                    library.localPlaylists.forEach { playlist ->
                        val screen = Screen.LocalPlaylist(playlist.id)
                        val mosaic = playlist.songs.mapNotNull { it.thumbnail }.distinct().take(4)
                        add(
                            SideLink(
                                playlist.name,
                                art = { MosaicArt(mosaic, SideRowLeadingSlot, RoundedCornerShape(6.dp)) },
                                screen = screen,
                                onClick = { navigator.navigate(screen) },
                                menu = { dismiss -> PlaylistLinkMenu(playlist.id, playlist.name, dismiss) },
                            ),
                        )
                    }
                    add(
                        SideLink("New Playlist", "add", onClick = {
                            Overlays.prompt("New playlist", confirm = "Create") { name ->
                                navigator.navigate(Screen.LocalPlaylist(Library.createPlaylist(name)))
                            }
                        }),
                    )
                },
            ),
            SideSection(
                null,
                listOf(
                    SideLink("History", "history", screen = Screen.History, onClick = { navigator.navigate(Screen.History) }),
                    SideLink("Stats", "stats", screen = Screen.TopSongs, onClick = { navigator.navigate(Screen.TopSongs) }),
                ),
            ),
        )
    }
}

@Composable
private fun PlaylistLinkMenu(id: String, name: String, dismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    com.convx.desktop.ui.components.MenuItem("Play", "play_applemusic") {
        Library.playlist(id)?.songs?.takeIf { it.isNotEmpty() }?.let { com.convx.desktop.player.PlayerController.playSongs(it, title = name) }
        dismiss()
    }
    com.convx.desktop.ui.components.MenuItem("Rename", "edit") {
        dismiss()
        Overlays.prompt("Rename playlist", initial = name) { Library.renamePlaylist(id, it) }
    }
    com.convx.desktop.ui.components.MenuItem("Delete", "delete", destructive = true) {
        dismiss()
        Overlays.confirm("Delete playlist?", "\"$name\" will be removed from your library.", "Delete") {
            if (navigator.current.screen == Screen.LocalPlaylist(id)) navigator.back()
            Library.deletePlaylist(id)
        }
    }
}

/** Fades content out over [edge] at the top and bottom, the phone's fadingEdge(vertical). */
fun Modifier.fadingEdge(edge: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = (edge.toPx() / size.height).coerceIn(0f, 0.5f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
