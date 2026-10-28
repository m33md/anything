/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.panel
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxFonts
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

private enum class LibraryFilter(val label: String) { Playlists("Playlists"), Albums("Albums"), Artists("Artists") }

/** One row in Your Library, whatever it points at. */
private data class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val mosaic: List<String> = emptyList(),
    val round: Boolean = false,
    val kind: LibraryFilter,
    val sortTime: Long,
    val screen: Screen,
)

@Composable
fun Sidebar(collapsed: Boolean, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    val width: Dp = if (collapsed) 76.dp else 300.dp

    val entries = remember(library) {
        buildList {
            library.localPlaylists.forEach { p ->
                add(
                    LibraryEntry(
                        key = p.id,
                        title = p.name,
                        subtitle = "Playlist • ${pluralize(p.songs.size, "song")}",
                        thumbnail = null,
                        mosaic = p.songs.mapNotNull { it.thumbnail }.distinct().take(4),
                        kind = LibraryFilter.Playlists,
                        sortTime = p.updatedAt,
                        screen = Screen.LocalPlaylist(p.id),
                    ),
                )
            }
            library.playlists.forEach { p ->
                add(LibraryEntry(p.id, p.title, listOfNotNull("Playlist", p.author).joinToString(" • "), p.thumbnail, kind = LibraryFilter.Playlists, sortTime = p.savedAt, screen = Screen.Playlist(p.id)))
            }
            library.albums.forEach { a ->
                add(
                    LibraryEntry(
                        a.browseId, a.title,
                        listOfNotNull("Album", a.artists.joinToString(", ") { it.name }.ifBlank { null }).joinToString(" • "),
                        a.thumbnail, kind = LibraryFilter.Albums, sortTime = a.savedAt, screen = Screen.Album(a.browseId),
                    ),
                )
            }
            library.artists.forEach { a ->
                add(LibraryEntry(a.id, a.name, "Artist", a.thumbnail, round = true, kind = LibraryFilter.Artists, sortTime = a.savedAt, screen = Screen.Artist(a.id)))
            }
        }.sortedByDescending { it.sortTime }
    }
    val shown = remember(entries, filter) { if (filter == null) entries else entries.filter { it.kind == filter } }

    Column(modifier.width(width).fillMaxHeight().animateContentSize()) {
        // Navigation card
        Column(
            Modifier.fillMaxWidth().panel().padding(vertical = 10.dp, horizontal = if (collapsed) 10.dp else 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = if (collapsed) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                ConvxIcon("convx_logo", tint = LocalAccent.current, size = 28.dp)
                if (!collapsed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Convx",
                        style = ConvxType.Title2.copy(fontFamily = ConvxFonts.Chillax, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, letterSpacing = 0.sp),
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val current = navigator.current.screen
            NavItem("Home", if (current == Screen.Home) "cosmos_home_filled" else "cosmos_home_outlined", current == Screen.Home, collapsed) {
                navigator.openRoot(Screen.Home)
            }
            val searching = current == Screen.Search || current is Screen.SearchResults
            NavItem("Search", "cosmos_search", searching, collapsed) {
                navigator.openRoot(Screen.Search)
                runCatching { UiState.searchFocus.requestFocus() }
            }
            NavItem("Library", if (current == Screen.Library) "cosmos_library_filled" else "cosmos_library_outlined", current == Screen.Library, collapsed) {
                navigator.openRoot(Screen.Library)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Your Library card
        Column(Modifier.fillMaxWidth().weight(1f).panel()) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (collapsed) 0.dp else 18.dp, end = if (collapsed) 0.dp else 10.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                if (collapsed) {
                    IconButton("sidebar", { AppSettings.update { it.copy(sidebarCollapsed = false) } }, tooltip = "Expand Your Library")
                } else {
                    Row(
                        Modifier.weight(1f).pressable({ AppSettings.update { it.copy(sidebarCollapsed = true) } }, pressScale = 1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Your Library", style = ConvxType.Headline, color = ConvxColors.SecondaryLabel)
                    }
                    IconButton(
                        "add",
                        {
                            Overlays.prompt("New playlist", confirm = "Create") { name ->
                                val id = Library.createPlaylist(name)
                                navigator.navigate(Screen.LocalPlaylist(id))
                            }
                        },
                        tooltip = "Create playlist",
                    )
                    IconButton("sidebar", { AppSettings.update { it.copy(sidebarCollapsed = true) } }, tooltip = "Collapse Your Library")
                }
            }
            if (!collapsed) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (filter != null) {
                        item {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).pressable({ filter = null }),
                                contentAlignment = Alignment.Center,
                            ) { ConvxIcon("close", tint = Color.White, size = 16.dp) }
                        }
                    }
                    items(LibraryFilter.entries.filter { filter == null || it == filter }) { f ->
                        Chip(f.label, filter == f, { filter = if (filter == f) null else f })
                    }
                }
            }
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (filter == null || filter == LibraryFilter.Playlists) {
                        item(key = "liked") {
                            LibraryRow(
                                title = "Liked Songs",
                                subtitle = "Playlist • ${pluralize(library.liked.size, "song")}",
                                collapsed = collapsed,
                                selected = navigator.current.screen == Screen.Liked,
                                art = { size -> GradientTile("favorite", size, listOf(Color(0xFFFA2D48), Color(0xFF8E2DE2))) },
                                onClick = { navigator.navigate(Screen.Liked) },
                                onPlay = { library.liked.map { it.song }.takeIf { it.isNotEmpty() }?.let { PlayerController.playSongs(it, title = "Liked Songs") } },
                            )
                        }
                        item(key = "history") {
                            LibraryRow(
                                title = "Recently Played",
                                subtitle = "History",
                                collapsed = collapsed,
                                selected = navigator.current.screen == Screen.History,
                                art = { size -> GradientTile("history", size, listOf(Color(0xFF2C7BE5), Color(0xFF1B3A6B))) },
                                onClick = { navigator.navigate(Screen.History) },
                            )
                        }
                        item(key = "top") {
                            LibraryRow(
                                title = "Your Top 50",
                                subtitle = "Most played",
                                collapsed = collapsed,
                                selected = navigator.current.screen == Screen.TopSongs,
                                art = { size -> GradientTile("stats", size, listOf(Color(0xFFFF9F0A), Color(0xFFB2361B))) },
                                onClick = { navigator.navigate(Screen.TopSongs) },
                            )
                        }
                    }
                    items(shown, key = { it.kind.name + it.key }) { entry ->
                        val menu = rememberMenuAnchor()
                        Box(Modifier.onSecondaryClick(menu.openAtProvider())) {
                            LibraryRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                collapsed = collapsed,
                                selected = navigator.current.screen == entry.screen,
                                art = { size ->
                                    if (entry.mosaic.isNotEmpty() || entry.thumbnail == null) {
                                        MosaicArt(entry.mosaic, size)
                                    } else {
                                        Artwork(
                                            entry.thumbnail,
                                            Modifier.size(size),
                                            size = 160,
                                            shape = if (entry.round) CircleShape else RoundedCornerShape(6.dp),
                                            placeholderIcon = if (entry.round) "person" else "music_note",
                                            iconSize = 20.dp,
                                        )
                                    }
                                },
                                onClick = { navigator.navigate(entry.screen) },
                            )
                            AppMenu(menu) { LibraryEntryMenu(entry) { menu.expanded = false } }
                        }
                    }
                    if (shown.isEmpty() && !collapsed && filter != null) {
                        item {
                            Text(
                                "Nothing here yet.",
                                style = ConvxType.Subhead,
                                color = ConvxColors.SecondaryLabel,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryEntryMenu(entry: LibraryEntry, dismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    MenuItem("Open", "arrow_forward") {
        navigator.navigate(entry.screen)
        dismiss()
    }
    when (val screen = entry.screen) {
        is Screen.LocalPlaylist -> {
            MenuItem("Play", "play_applemusic") {
                Library.playlist(screen.id)?.songs?.takeIf { it.isNotEmpty() }?.let { PlayerController.playSongs(it, title = entry.title) }
                dismiss()
            }
            MenuItem("Rename", "edit") {
                dismiss()
                Overlays.prompt("Rename playlist", initial = entry.title) { Library.renamePlaylist(screen.id, it) }
            }
            MenuItem("Delete", "delete", destructive = true) {
                dismiss()
                Overlays.confirm("Delete playlist?", "\"${entry.title}\" will be removed from your library.", "Delete") {
                    if (navigator.current.screen == screen) navigator.back()
                    Library.deletePlaylist(screen.id)
                }
            }
        }
        is Screen.Album -> MenuItem("Remove from Library", "library_add_check") {
            Library.value.albums.firstOrNull { it.browseId == screen.browseId }?.let { Library.toggleAlbum(it) }
            dismiss()
        }
        is Screen.Playlist -> MenuItem("Remove from Library", "library_add_check") {
            Library.value.playlists.firstOrNull { it.id == screen.id }?.let { Library.togglePlaylist(it) }
            dismiss()
        }
        is Screen.Artist -> MenuItem("Unfollow", "check") {
            Library.value.artists.firstOrNull { it.id == screen.browseId }?.let { Library.toggleArtist(it) }
            dismiss()
        }
        else -> Unit
    }
}

@Composable
private fun NavItem(label: String, icon: String, selected: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = if (selected || hovered) Color.White else ConvxColors.SecondaryLabel
    val content = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(ConvxShapes.Control)
                .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                .hoverable(interaction)
                .pressable(onClick, interaction = interaction, pressScale = 0.98f)
                .padding(horizontal = if (collapsed) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            ConvxIcon(icon, tint = color, size = 24.dp)
            if (!collapsed) {
                Spacer(Modifier.width(16.dp))
                Text(label, style = ConvxType.BodyStrong.copy(fontSize = 16.sp), color = color)
            }
        }
    }
    if (collapsed) Tooltip(label) { content() } else content()
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    collapsed: Boolean,
    selected: Boolean,
    art: @Composable (Dp) -> Unit,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ConvxShapes.Control)
                .background(
                    when {
                        selected -> Color.White.copy(alpha = 0.1f)
                        hovered -> Color.White.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .pressable(onClick, interaction = interaction, pressScale = 0.99f)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            Box {
                art(48.dp)
                if (hovered && onPlay != null) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).pressable(onPlay),
                        contentAlignment = Alignment.Center,
                    ) { ConvxIcon("play_applemusic", tint = Color.White, size = 22.dp) }
                }
            }
            if (!collapsed) {
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(title, style = ConvxType.Body, color = if (selected) LocalAccent.current else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (collapsed) Tooltip(title) { row() } else row()
}

@Composable
fun GradientTile(icon: String, size: Dp, colors: List<Color>, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(
        Modifier.size(size).clip(shape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = Color.White, size = size * 0.45f)
    }
}

/** A playlist's cover: its first four covers in a 2x2 grid, or the first one, or a note. */
@Composable
fun MosaicArt(thumbnails: List<String>, size: Dp, shape: Shape = RoundedCornerShape(6.dp)) {
    when {
        thumbnails.size >= 4 -> Column(Modifier.size(size).clip(shape)) {
            for (r in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (c in 0 until 2) {
                        Artwork(thumbnails[r * 2 + c], Modifier.weight(1f).fillMaxHeight(), size = 160, shape = RoundedCornerShape(0.dp))
                    }
                }
            }
        }
        thumbnails.isNotEmpty() -> Artwork(thumbnails.first(), Modifier.size(size), size = 300, shape = shape)
        else -> Box(
            Modifier.size(size).clip(shape).background(ConvxColors.CardTertiary),
            contentAlignment = Alignment.Center,
        ) { ConvxIcon("queue_music", tint = ConvxColors.SecondaryLabel, size = size * 0.42f) }
    }
}
