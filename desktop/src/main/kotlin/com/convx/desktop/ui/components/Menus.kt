/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.glass.InteractiveHighlight
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.glass.MenuGlass
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

/** Where a menu opens: under its button, or at the right-click point. */
class MenuAnchor {
    var expanded by mutableStateOf(false)
    var offset by mutableStateOf(DpOffset.Zero)
}

@Composable
fun rememberMenuAnchor() = remember { MenuAnchor() }

/** Opens [anchor] at a right-click [position] (px, relative to the clicked element). */
@Composable
fun MenuAnchor.openAtProvider(): (Offset) -> Unit {
    val density = LocalDensity.current
    return { position ->
        with(density) { offset = DpOffset(position.x.toDp(), position.y.toDp()) }
        expanded = true
    }
}

@Composable
fun AppMenu(anchor: MenuAnchor, content: @Composable () -> Unit) {
    // The phone's menus are liquid glass over the page, lit where the pointer is.
    val backdrop = LocalMenuBackdrop.current
    val scope = rememberCoroutineScope()
    val glow = remember(scope) { InteractiveHighlight(scope, radiusScale = 0.6f, sheenAlpha = 0.06f, spotAlpha = 0.32f) }
    val glass = if (backdrop != null) {
        Modifier
            .liquidGlass(MenuShape, MenuGlass, highlightAlpha = 0.20f, outerShadow = false, backdrop = backdrop)
            .then(glow.modifier)
            .then(glow.gestureModifier)
    } else {
        Modifier
    }
    // A zero-size box at the click point gives the popup something to hang from.
    Box(Modifier.offset(anchor.offset.x, anchor.offset.y).size(0.dp)) {
        DropdownMenu(
            expanded = anchor.expanded,
            onDismissRequest = { anchor.expanded = false },
            modifier = glass.widthIn(min = 220.dp).heightIn(max = 560.dp),
            shape = MenuShape,
            containerColor = if (backdrop != null) Color.Transparent else Color(0xFF2C2C2E),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = if (backdrop != null) null else BorderStroke(0.5.dp, ConvxColors.Separator),
        ) {
            content()
        }
    }
}

/** The phone's menus round off at 32 on a 270-wide panel; a desktop menu is smaller. */
private val MenuShape = RoundedCornerShape(20.dp)

@Composable
fun MenuItem(text: String, icon: String? = null, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> ConvxColors.TertiaryLabel
        destructive -> Color(0xFFFF453A)
        else -> Color.White
    }
    DropdownMenuItem(
        text = { Text(text, style = ConvxType.Body, color = color) },
        leadingIcon = icon?.let { { ConvxIcon(it, tint = color, size = 18.dp) } },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
    )
}

@Composable
fun MenuDivider() = HorizontalDivider(color = ConvxColors.Separator, thickness = 0.5.dp)

/** Everything you can do with one song — the phone's song sheet, as a desktop menu. */
@Composable
fun SongMenuContent(
    song: Song,
    dismiss: () -> Unit,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    var choosingPlaylist by remember { mutableStateOf(false) }

    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, listOf(song))
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, listOf(song))
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        return
    }

    MenuItem("Play next", "playlist_play") {
        PlayerController.playNext(listOf(song))
        Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        PlayerController.addToQueue(listOf(song))
        Overlays.toast("Added to queue")
        dismiss()
    }
    MenuItem("Start radio", "radio") {
        PlayerController.startRadio(song)
        dismiss()
    }
    MenuDivider()
    MenuItem(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", if (liked) "favorite" else "favorite_border") {
        Library.toggleLike(song)
        Overlays.toast(if (liked) "Removed from Liked Songs" else "Added to Liked Songs", if (liked) "favorite_border" else "favorite")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    MenuDivider()
    song.artists.firstOrNull { it.id != null }?.let { artist ->
        MenuItem("Go to artist", "artist") {
            navigator.navigate(Screen.Artist(artist.id!!))
            dismiss()
        }
    }
    song.album?.let { album ->
        MenuItem("Go to album", "album") {
            navigator.navigate(Screen.Album(album.id))
            dismiss()
        }
    }
    MenuItem("Copy link", "link") {
        clipboard.setText(AnnotatedString("https://music.youtube.com/watch?v=${song.id}"))
        Overlays.toast("Link copied", "link")
        dismiss()
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}

/** Menu for a whole collection: album, playlist, mix. */
@Composable
fun CollectionMenuContent(
    songs: () -> List<Song>,
    dismiss: () -> Unit,
    saved: Boolean? = null,
    onToggleSaved: (() -> Unit)? = null,
    link: String? = null,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    var choosingPlaylist by remember { mutableStateOf(false) }
    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, songs())
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, songs())
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        return
    }
    MenuItem("Shuffle", "shuffle") {
        PlayerController.playSongs(songs(), shuffle = true)
        dismiss()
    }
    MenuItem("Play next", "playlist_play") {
        PlayerController.playNext(songs())
        Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        PlayerController.addToQueue(songs())
        Overlays.toast("Added to queue")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    if (saved != null && onToggleSaved != null) {
        MenuItem(if (saved) "Remove from Library" else "Save to Library", if (saved) "library_add_check" else "library_add") {
            onToggleSaved()
            dismiss()
        }
    }
    if (link != null) {
        MenuItem("Copy link", "link") {
            clipboard.setText(AnnotatedString(link))
            Overlays.toast("Link copied", "link")
            dismiss()
        }
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}
