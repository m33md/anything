/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SongShelf
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.shell.ExploreAnchor
import com.convx.desktop.ui.shell.LibraryTab
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

/** A titled run of shelves: what charts, moods and "see all" links open. */
private data class BrowsePage(val title: String?, val shelves: List<Pair<String?, List<YTItem>>>)

@Composable
fun BrowseScreen(browseId: String, params: String?, title: String?) {
    // Some "see all" links are really an album, playlist or artist.
    when {
        browseId.startsWith("VL") -> return PlaylistScreen(browseId)
        browseId.startsWith("MPREb") -> return AlbumScreen(browseId)
        browseId.startsWith("UC") && params == null -> return ArtistScreen(browseId)
    }
    val settings by AppSettings.state.collectAsState()
    val navigator = LocalNavigator.current

    if (browseId == "FEmusic_moods_and_genres") {
        val moods = rememberPage("moods") { YouTube.moodAndGenres().getOrThrow() }
        PageList {
            item { Text(title ?: "Moods & Genres", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
            val groups = moods.value
            when {
                groups != null -> groups.forEach { group ->
                    item(key = group.title) {
                        Column(Modifier.padding(bottom = 18.dp)) {
                            Text(group.title, style = ConvxType.Title3, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = Gutter),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                group.items.forEach { mood ->
                                    MoodTile(mood) { navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) }
                                }
                            }
                        }
                    }
                }
                moods.error != null -> item { MessageState("Couldn't load genres", friendlyError(moods.error), actionText = "Try again", onAction = { moods.retry() }) }
                else -> item { LoadingState() }
            }
        }
        return
    }

    val page = rememberPage("browse:$browseId:$params") {
        when (browseId) {
            "FEmusic_charts" -> {
                val charts = YouTube.getChartsPage().getOrThrow()
                BrowsePage("Charts", charts.sections.map { it.title to it.items })
            }
            "FEmusic_new_releases_albums" -> BrowsePage("New Releases", listOf(null to YouTube.newReleaseAlbums().getOrThrow()))
            else -> {
                val result = YouTube.browse(browseId, params).getOrThrow()
                BrowsePage(result.title, result.items.map { it.title to it.items })
            }
        }
    }
    PageList {
        item(key = "title") {
            Text(page.value?.title ?: title ?: "", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        val data = page.value
        when {
            data != null -> {
                val shelves = data.shelves
                    .map { (t, items) -> t to items.filtered(settings).distinctBy { it.id } }
                    .filter { it.second.isNotEmpty() }
                if (shelves.isEmpty()) item { MessageState("Nothing here right now", icon = "music_note") }
                if (shelves.size == 1) {
                    // A single list reads better as a grid than a lone sideways shelf.
                    val (t, items) = shelves.first()
                    if (t != null) item { Text(t, style = ConvxType.Title2, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
                    item(key = "grid") {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { items.forEach { ItemCard(it) } }
                    }
                } else {
                    shelves.forEachIndexed { index, (t, items) ->
                        item(key = "b-$index-$t") {
                            if (items.isSongShelf()) {
                                val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
                                SongShelf(t ?: "Songs", songs) { i -> PlayerController.playSongs(songs, i, t ?: data.title) }
                            } else {
                                ItemShelf(t ?: "", items)
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
            page.error != null -> item { MessageState("Couldn't load this page", friendlyError(page.error), actionText = "Try again", onAction = { page.retry() }) }
            else -> item { LoadingState() }
        }
    }
}

/** Your Library as a full page of cards, like the phone's library grid. */
@Composable
fun LibraryScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val tab = UiState.libraryTab
    PageList {
        // The filters live in Library's strip under the top bar.
        item(key = "title") {
            Text(tab.title, style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        item(key = "grid") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == LibraryTab.All || tab == LibraryTab.Playlists) {
                    val liked = library.liked.map { it.song }
                    SavedCard(
                        "Liked Songs",
                        pluralize(liked.size, "song"),
                        art = { _ -> GradientTile("favorite", 156.dp, listOf(Color(0xFFFA2D48), Color(0xFF8E2DE2)), RoundedCornerShape(8.dp)) },
                        onClick = { navigator.navigate(Screen.Liked) },
                        onPlay = if (liked.isEmpty()) null else ({ PlayerController.playSongs(liked, 0, "Liked Songs") }),
                    )
                    library.localPlaylists.forEach { p ->
                        SavedCard(
                            p.name,
                            "Playlist • ${pluralize(p.songs.size, "song")}",
                            art = { _ -> MosaicArt(p.songs.mapNotNull { it.thumbnail }.distinct().take(4), 156.dp, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.LocalPlaylist(p.id)) },
                            onPlay = if (p.songs.isEmpty()) null else ({ PlayerController.playSongs(p.songs, 0, p.name) }),
                        )
                    }
                    library.playlists.forEach { p ->
                        SavedCard(
                            p.title,
                            listOfNotNull("Playlist", p.author).joinToString(" • "),
                            thumbnail = p.thumbnail,
                            onClick = { navigator.navigate(Screen.Playlist(p.id)) },
                        )
                    }
                }
                if (tab == LibraryTab.All || tab == LibraryTab.Albums) {
                    library.albums.forEach { a ->
                        SavedCard(
                            a.title,
                            a.artists.joinToString(", ") { it.name }.ifBlank { "Album" },
                            thumbnail = a.thumbnail,
                            onClick = { navigator.navigate(Screen.Album(a.browseId)) },
                        )
                    }
                }
                if (tab == LibraryTab.All || tab == LibraryTab.Artists) {
                    library.artists.forEach { a ->
                        SavedCard(a.name, "Artist", thumbnail = a.thumbnail, round = true, onClick = { navigator.navigate(Screen.Artist(a.id)) })
                    }
                }
            }
        }
        val empty = when (tab) {
            LibraryTab.All -> false
            LibraryTab.Playlists -> false
            LibraryTab.Albums -> library.albums.isEmpty()
            LibraryTab.Artists -> library.artists.isEmpty()
        }
        if (empty) {
            item {
                MessageState(
                    if (tab == LibraryTab.Albums) "No saved albums yet" else "You don't follow any artists yet",
                    if (tab == LibraryTab.Albums) "Save an album with the + button on its page." else "Follow artists from their pages.",
                    icon = if (tab == LibraryTab.Albums) "album" else "artist",
                )
            }
        }
    }
}

@Composable
private fun SavedCard(
    title: String,
    subtitle: String,
    thumbnail: String? = null,
    round: Boolean = false,
    art: (@Composable (Modifier) -> Unit)? = null,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    width: Dp = 176.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape: Shape = if (round) CircleShape else ConvxShapes.Artwork
    Box(
        Modifier
            .width(width)
            .clip(ConvxShapes.Card)
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.98f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(if (hovered) 14.dp else 6.dp, shape)) {
                if (art != null) {
                    Box(Modifier.fillMaxSize().clip(shape), contentAlignment = Alignment.Center) { art(Modifier.fillMaxSize()) }
                } else {
                    Artwork(thumbnail, Modifier.fillMaxSize(), size = 400, shape = shape, placeholderIcon = if (round) "person" else "music_note", iconSize = 40.dp)
                }
                if (hovered && onPlay != null) {
                    PlayButton(false, onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp).shadow(10.dp, CircleShape), size = 46.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = ConvxType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                subtitle,
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The phone's Explore tab: new releases, the charts and the mood and genre tiles. Reads
 * the same cached pages as Home, so switching between them doesn't refetch.
 */
@Composable
fun ExploreScreen() {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val explore = rememberPage("explore") { YouTube.explore().getOrThrow() }
    val charts = rememberPage("charts") { YouTube.getChartsPage().getOrThrow() }
    val releases = explore.value?.newReleaseAlbums.orEmpty().filtered(settings)
    val chartShelves = charts.value?.sections.orEmpty()
        .map { it to it.items.filtered(settings).distinctBy { item -> item.id } }
        .filter { it.second.isNotEmpty() }
    val moods = explore.value?.moodAndGenres.orEmpty().distinctBy { it.title }

    // Where each anchor of Explore's strip sits in the list: the title is row 0.
    val anchors = buildMap {
        var row = 1
        if (releases.isNotEmpty()) put(ExploreAnchor.NewReleases, row++)
        if (chartShelves.isNotEmpty()) put(ExploreAnchor.Charts, row)
        row += chartShelves.size
        if (moods.isNotEmpty()) put(ExploreAnchor.Moods, row)
    }
    val listState = rememberLazyListState()
    // While the strip's jump scrolls, the chip that was picked stays lit; scrolling by hand
    // lights the shelf at the top (the first one while the title is still showing).
    var jumping by remember { mutableStateOf(false) }
    val jump = UiState.exploreJump
    LaunchedEffect(jump, anchors) {
        val target = jump ?: return@LaunchedEffect
        val row = anchors[target] ?: return@LaunchedEffect
        UiState.exploreJump = null
        UiState.exploreInView = target
        jumping = true
        try {
            listState.animateScrollToItem(row)
        } finally {
            jumping = false
        }
    }
    LaunchedEffect(listState, anchors) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (jumping) return@collect
            UiState.exploreInView = anchors.entries.filter { it.value <= maxOf(first, 1) }.maxByOrNull { it.value }?.key
        }
    }

    PageList(state = listState) {
        item(key = "title") {
            Text("Explore", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        if (releases.isNotEmpty()) {
            item(key = "new_releases") {
                ItemShelf("New Releases", releases, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_new_releases_albums", null, "New Releases")) })
                Spacer(Modifier.height(20.dp))
            }
        }
        chartShelves.forEachIndexed { index, (section, items) ->
            item(key = "chart-$index-${section.title}") {
                if (items.isSongShelf()) {
                    val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
                    SongShelf(section.title, songs) { i -> PlayerController.playSongs(songs, i, section.title) }
                } else {
                    ItemShelf(section.title, items, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_charts", null, "Charts")) })
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (moods.isNotEmpty()) {
            item(key = "moods") { MoodGrid("Moods & Genres", moods) }
        }
        when {
            explore.value != null || charts.value != null -> Unit
            explore.error != null -> item {
                MessageState(
                    "Couldn't reach YouTube Music",
                    friendlyError(explore.error),
                    icon = "offline",
                    actionText = "Try again",
                    onAction = {
                        explore.retry()
                        charts.retry()
                    },
                )
            }
            else -> item { LoadingState() }
        }
    }
}
