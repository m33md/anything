/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.formatTotal
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.CollectionMenuContent
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LinkText
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.components.SongTableHeader
import com.convx.desktop.ui.components.rememberHeroColors
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.GradientTile
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.MosaicArt
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.TopBarHeight
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val DefaultHeroTint = Color(0xFF3A3A3C)

/**
 * The top of an album or playlist page: cover, kind, a big title, the byline, all on a
 * wash of the cover's own colour that fades down into the song list.
 */
fun LazyListScope.collectionHero(
    tint: Color,
    kind: String,
    title: String,
    art: @Composable (Dp) -> Unit,
    byline: @Composable () -> Unit,
    description: String? = null,
) {
    item(key = "hero") {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.6f))))
                .padding(start = Gutter, end = Gutter, top = TopBarHeight + 8.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.shadow(28.dp, RoundedCornerShape(10.dp))) { art(232.dp) }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind, style = ConvxType.SubheadStrong, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    val size = when {
                        title.length <= 18 -> 64.sp
                        title.length <= 34 -> 46.sp
                        else -> 32.sp
                    }
                    Text(
                        title,
                        style = ConvxType.Hero.copy(fontSize = size, lineHeight = size * 1.08f),
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(description, style = ConvxType.Subhead, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    byline()
                }
            }
        }
    }
}

/** Play, shuffle, save and more, on the faded tail of the hero wash. */
fun LazyListScope.collectionActions(
    tint: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playing: Boolean,
    saved: Boolean? = null,
    onToggleSaved: (() -> Unit)? = null,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    item(key = "actions") {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.6f), Color.Transparent)))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlayButton(playing, onPlay, size = 58.dp, color = LocalAccent.current, glyph = Color.White)
                IconButton("shuffle", onShuffle, size = 44.dp, iconSize = 26.dp, tooltip = "Shuffle")
                if (saved != null && onToggleSaved != null) {
                    IconButton(
                        if (saved) "library_add_check" else "library_add",
                        onToggleSaved,
                        size = 44.dp,
                        iconSize = 26.dp,
                        tint = if (saved) LocalAccent.current else Color.White,
                        tooltip = if (saved) "Remove from Your Library" else "Save to Your Library",
                    )
                }
                if (menu != null) {
                    val anchor = rememberMenuAnchor()
                    Box {
                        IconButton("more_horiz", { anchor.expanded = true }, size = 44.dp, iconSize = 26.dp, tooltip = "More options")
                        AppMenu(anchor) { menu { anchor.expanded = false } }
                    }
                }
            }
        }
    }
}

/** A numbered table of songs; clicking one plays the whole list from there. */
fun LazyListScope.songTable(
    songs: List<Song>,
    queueTitle: String,
    keyPrefix: String,
    showAlbum: Boolean = true,
    showArtwork: Boolean = true,
    trailingLabel: String? = null,
    trailing: ((Int) -> String?)? = null,
    menuExtra: (@Composable (index: Int, song: Song, dismiss: () -> Unit) -> Unit)? = null,
) {
    item(key = "$keyPrefix-header") {
        SongTableHeader(showAlbum = showAlbum, trailingLabel = trailingLabel, modifier = Modifier.padding(horizontal = Gutter))
    }
    itemsIndexed(songs, key = { i, s -> "$keyPrefix-$i-${s.id}" }) { i, song ->
        Box(Modifier.padding(horizontal = Gutter - 12.dp)) {
            SongRow(
                song,
                { PlayerController.playSongs(songs, i, queueTitle) },
                index = i + 1,
                showArtwork = showArtwork,
                showAlbum = showAlbum,
                trailingText = trailing?.invoke(i),
                menuExtra = if (menuExtra == null) null else ({ dismiss -> menuExtra(i, song, dismiss) }),
            )
        }
    }
}

/** "Artist, Artist • 2024 • 12 songs, 41 min": linked names first, then plain facts. */
@Composable
private fun Byline(parts: List<String>, names: List<Pair<String, String?>> = emptyList()) {
    val navigator = LocalNavigator.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, (name, artistId) ->
            val text = name + if (i < names.lastIndex) ", " else ""
            if (artistId != null) LinkText(text, ConvxType.SubheadStrong.copy(color = Color.White)) { navigator.navigate(Screen.Artist(artistId)) }
            else Text(text, style = ConvxType.SubheadStrong, color = Color.White, maxLines = 1)
        }
        val text = parts.filter { it.isNotBlank() }.joinToString(" • ")
        if (text.isNotEmpty()) {
            Text(
                (if (names.isNotEmpty()) " • " else "") + text,
                style = ConvxType.Subhead,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun List<Song>.totalLine(): List<String> {
    val seconds = sumOf { (it.duration ?: 0).toLong() }
    return listOfNotNull(pluralize(size, "song"), if (seconds > 0) formatTotal(seconds) else null)
}

@Composable
fun AlbumScreen(browseId: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val page = rememberPage("album:$browseId") { YouTube.album(browseId).getOrThrow() }
    val hero by rememberHeroColors(page.value?.album?.thumbnail)
    val tint = hero?.background ?: DefaultHeroTint
    val listState = rememberLazyListState()

    PageContent(page, Modifier.padding(top = TopBarHeight)) { album ->
        val songs = remember(album) { album.songs.map { it.toSong() } }
        val saved = library.albums.any { it.browseId == browseId }
        val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
        PageList(state = listState, tint = tint, topPadding = 0.dp) {
            collectionHero(
                tint = tint,
                kind = "Album",
                title = album.album.title,
                art = { size -> Artwork(album.album.thumbnail, Modifier.size(size), size = 600, shape = RoundedCornerShape(10.dp), iconSize = 64.dp) },
                byline = {
                    Byline(
                        listOfNotNull(album.album.year?.toString()) + songs.totalLine(),
                        album.album.artists.orEmpty().map { it.name to it.id },
                    )
                },
            )
            collectionActions(
                tint = tint,
                onPlay = { if (isThisPlaying) PlayerController.pause() else PlayerController.playSongs(songs, 0, album.album.title, shuffle = false) },
                onShuffle = { PlayerController.playSongs(songs, 0, album.album.title, shuffle = true) },
                playing = isThisPlaying,
                saved = saved,
                onToggleSaved = {
                    Library.toggleAlbum(album.album.toSaved())
                    Overlays.toast(if (saved) "Removed from Your Library" else "Saved to Your Library")
                },
                menu = { dismiss ->
                    CollectionMenuContent(
                        songs = { songs },
                        dismiss = dismiss,
                        link = "https://music.youtube.com/browse/$browseId",
                    )
                },
            )
            songTable(songs, album.album.title, "album", showAlbum = false, showArtwork = false)
            album.description?.takeIf { it.isNotBlank() }?.let { text ->
                item(key = "desc") {
                    Text(
                        text,
                        style = ConvxType.Subhead,
                        color = ConvxColors.SecondaryLabel,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 20.dp).widthIn(max = 760.dp),
                    )
                }
            }
            if (album.otherVersions.isNotEmpty()) {
                item(key = "versions") {
                    Spacer(Modifier.height(16.dp))
                    ItemShelf("Other versions", album.otherVersions)
                }
            }
            if (album.releasesForYou.isNotEmpty()) {
                item(key = "foryou") {
                    Spacer(Modifier.height(16.dp))
                    ItemShelf("Releases for you", album.releasesForYou)
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val playlistId = id.removePrefix("VL")
    val page = rememberPage("playlist:$playlistId") { YouTube.playlist(playlistId).getOrThrow() }
    val hero by rememberHeroColors(page.value?.playlist?.thumbnail)
    val tint = hero?.background ?: DefaultHeroTint
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }

    PageContent(page, Modifier.padding(top = TopBarHeight)) { data ->
        val songs = remember(data) { data.songs.map { it.toSong() } }
        val playlist = data.playlist
        val saved = library.playlists.any { it.id == playlist.id }
        val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
        PageList(
            state = listState,
            tint = tint,
            topPadding = 0.dp,
            onNearEnd = {
                val continuation = page.value?.songsContinuation ?: return@PageList
                if (loadingMore) return@PageList
                loadingMore = true
                scope.launch {
                    val more = withContext(Dispatchers.IO) { YouTube.playlistContinuation(continuation).getOrNull() }
                    if (more != null) page.update { it.copy(songs = it.songs + more.songs, songsContinuation = more.continuation) }
                    loadingMore = false
                }
            },
        ) {
            collectionHero(
                tint = tint,
                kind = "Playlist",
                title = playlist.title,
                art = { size -> Artwork(playlist.thumbnail, Modifier.size(size), size = 600, shape = RoundedCornerShape(10.dp), iconSize = 64.dp) },
                byline = {
                    Byline(
                        listOfNotNull(playlist.songCountText) + if (data.songsContinuation == null) listOfNotNull(songs.totalLine().getOrNull(1)) else emptyList(),
                        listOfNotNull(playlist.author?.let { it.name to it.id }),
                    )
                },
            )
            collectionActions(
                tint = tint,
                onPlay = {
                    when {
                        isThisPlaying -> PlayerController.pause()
                        songs.isNotEmpty() -> PlayerController.playSongs(songs, 0, playlist.title, shuffle = false)
                        playlist.playEndpoint != null -> PlayerController.playEndpoint(playlist.playEndpoint!!, playlist.title)
                    }
                },
                onShuffle = {
                    val shuffle = playlist.shuffleEndpoint
                    if (data.songsContinuation != null && shuffle != null) PlayerController.playEndpoint(shuffle, playlist.title)
                    else PlayerController.playSongs(songs, 0, playlist.title, shuffle = true)
                },
                playing = isThisPlaying,
                saved = saved,
                onToggleSaved = {
                    Library.togglePlaylist(playlist.toSaved())
                    Overlays.toast(if (saved) "Removed from Your Library" else "Saved to Your Library")
                },
                menu = { dismiss ->
                    CollectionMenuContent(
                        songs = { songs },
                        dismiss = dismiss,
                        link = "https://music.youtube.com/playlist?list=$playlistId",
                        extra = { close ->
                            MenuItem("Start radio", "radio") {
                                com.convx.desktop.ui.components.ItemActions.radio(playlist)
                                close()
                            }
                        },
                    )
                },
            )
            if (songs.isEmpty()) {
                item { MessageState("This playlist is empty", icon = "queue_music") }
            } else {
                songTable(songs, playlist.title, "pl")
            }
            if (loadingMore) item { LoadingState() }
            data.related?.takeIf { it.isNotEmpty() }?.let { related ->
                item(key = "related") {
                    Spacer(Modifier.height(20.dp))
                    ItemShelf("Related playlists", related)
                }
            }
        }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val playlist = library.localPlaylists.firstOrNull { it.id == id }
    if (playlist == null) {
        Box(Modifier.padding(top = TopBarHeight)) { MessageState("This playlist was deleted", icon = "delete") }
        return
    }
    val songs = playlist.songs
    val thumbs = remember(songs) { songs.mapNotNull { it.thumbnail }.distinct().take(4) }
    val hero by rememberHeroColors(thumbs.firstOrNull())
    val tint = hero?.background ?: DefaultHeroTint
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
        collectionHero(
            tint = tint,
            kind = "Playlist",
            title = playlist.name,
            art = { size -> MosaicArt(thumbs, size, RoundedCornerShape(10.dp)) },
            byline = { Byline(listOf("Updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(playlist.updatedAt))) + songs.totalLine()) },
        )
        collectionActions(
            tint = tint,
            onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, playlist.name, shuffle = false) },
            onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, playlist.name, shuffle = true) },
            playing = isThisPlaying,
            menu = { dismiss ->
                MenuItem("Rename", "edit") {
                    dismiss()
                    Overlays.prompt("Rename playlist", initial = playlist.name) { Library.renamePlaylist(id, it) }
                }
                MenuItem("Add to queue", "queue_music", enabled = songs.isNotEmpty()) {
                    PlayerController.addToQueue(songs)
                    Overlays.toast("Added to queue")
                    dismiss()
                }
                MenuItem("Delete playlist", "delete", destructive = true) {
                    dismiss()
                    Overlays.confirm("Delete playlist?", "\"${playlist.name}\" will be removed from your library.", "Delete") {
                        navigator.back()
                        Library.deletePlaylist(id)
                    }
                }
            },
        )
        if (songs.isEmpty()) {
            item {
                MessageState(
                    "Let's find something for your playlist",
                    "Right-click any song and choose Add to playlist.",
                    icon = "playlist_add",
                    actionText = "Search",
                    onAction = { navigator.navigate(Screen.Search) },
                )
            }
        } else {
            songTable(
                songs,
                playlist.name,
                "local",
                menuExtra = { i, song, dismiss ->
                    if (i > 0) {
                        MenuItem("Move up", "expand_less") {
                            Library.moveInPlaylist(id, i, i - 1)
                            dismiss()
                        }
                    }
                    if (i < songs.lastIndex) {
                        MenuItem("Move down", "expand_more") {
                            Library.moveInPlaylist(id, i, i + 1)
                            dismiss()
                        }
                    }
                    MenuItem("Remove from this playlist", "remove", destructive = true) {
                        Library.removeFromPlaylist(id, song.id)
                        dismiss()
                    }
                },
            )
        }
    }
}

@Composable
fun LikedScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val songs = remember(library.liked) { library.liked.map { it.song } }
    val tint = Color(0xFF5B2A86)
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    val dates = remember(library.liked) { library.liked.map { it.likedAt } }
    PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
        collectionHero(
            tint = tint,
            kind = "Playlist",
            title = "Liked Songs",
            art = { size -> GradientTile("favorite", size, listOf(Color(0xFFFA2D48), Color(0xFF8E2DE2)), RoundedCornerShape(10.dp)) },
            byline = { Byline(songs.totalLine()) },
        )
        collectionActions(
            tint = tint,
            onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Liked Songs", shuffle = false) },
            onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Liked Songs", shuffle = true) },
            playing = isThisPlaying,
        )
        if (songs.isEmpty()) {
            item {
                MessageState(
                    "Songs you like will appear here",
                    "Save songs by clicking the heart.",
                    icon = "favorite_border",
                    actionText = "Find songs",
                    onAction = { navigator.navigate(Screen.Search) },
                )
            }
        } else {
            songTable(songs, "Liked Songs", "liked", trailingLabel = "Date added", trailing = { i -> dates.getOrNull(i)?.let { relativeDate(it) } })
        }
    }
}

@Composable
fun HistoryScreen() {
    val library by Library.state.collectAsState()
    val events = library.history
    val songs = remember(events) { events.map { it.song } }
    PageList(state = rememberLazyListState()) {
        item(key = "title") {
            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently played", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                if (events.isNotEmpty()) {
                    com.convx.desktop.ui.components.PillButton("Clear history", {
                        Overlays.confirm("Clear listening history?", "This also resets Your Top 50.", "Clear") { Library.clearHistory() }
                    })
                }
            }
        }
        if (events.isEmpty()) {
            item { MessageState("Nothing played yet", "Songs you play show up here.", icon = "history") }
        } else {
            songTable(
                songs.take(500),
                "Recently played",
                "history",
                trailingLabel = "Played",
                trailing = { i -> events.getOrNull(i)?.let { relativeDate(it.at) } },
                menuExtra = { i, _, dismiss ->
                    MenuItem("Remove from history", "remove") {
                        events.getOrNull(i)?.let { Library.removeFromHistory(it.at) }
                        dismiss()
                    }
                },
            )
        }
    }
}

@Composable
fun TopSongsScreen() {
    val library by Library.state.collectAsState()
    val top = remember(library.history) { Library.topSongs(50) }
    val songs = remember(top) { top.map { it.first } }
    val tint = Color(0xFF8A3B12)
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
        collectionHero(
            tint = tint,
            kind = "Playlist",
            title = "Your Top 50",
            art = { size -> GradientTile("stats", size, listOf(Color(0xFFFF9F0A), Color(0xFFB2361B)), RoundedCornerShape(10.dp)) },
            byline = { Byline(listOf("Your most played songs") + songs.totalLine()) },
        )
        collectionActions(
            tint = tint,
            onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Your Top 50", shuffle = false) },
            onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Your Top 50", shuffle = true) },
            playing = isThisPlaying,
        )
        if (songs.isEmpty()) {
            item { MessageState("No plays yet", "Your most played songs will rank here.", icon = "stats") }
        } else {
            songTable(songs, "Your Top 50", "top", trailingLabel = "Plays", trailing = { i -> top.getOrNull(i)?.second?.let { pluralize(it, "play") } })
        }
    }
}

/** "Today", "Yesterday", "3 days ago", or a date. */
fun relativeDate(millis: Long): String {
    val days = ((System.currentTimeMillis() - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 28 -> "${days / 7} week${if (days / 7 == 1) "" else "s"} ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
