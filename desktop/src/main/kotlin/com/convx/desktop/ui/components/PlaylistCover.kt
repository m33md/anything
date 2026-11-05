/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.PlaylistCovers
import com.convx.desktop.ui.screens.settings.pickFile
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens Windows' picker and makes the picture [playlistId]'s cover. */
fun choosePlaylistCover(playlistId: String) {
    val file = pickFile("Choose a picture for the playlist", PlaylistCovers.Extensions) ?: return
    AppScope.launch(Dispatchers.IO) {
        when (val result = PlaylistCovers.import(playlistId, file)) {
            is PlaylistCovers.Result.Done -> Library.setPlaylistCover(playlistId, result.cover)
            is PlaylistCovers.Result.Failed -> withContext(Dispatchers.Main) { Overlays.toast(result.reason, "error") }
        }
    }
}

/** The phone's Liked Songs colours: Convx red into violet. */
val LikedGradient = listOf(Color(0xFFFA2D48), Color(0xFF8E2DE2))

/** Opens Windows' picker and makes the picture Liked Songs' cover. */
fun chooseLikedSongsCover() {
    val file = pickFile("Choose a picture for Liked Songs", PlaylistCovers.Extensions) ?: return
    AppScope.launch(Dispatchers.IO) {
        when (val result = PlaylistCovers.import("liked-songs", file)) {
            is PlaylistCovers.Result.Done -> setLikedSongsCover(result.cover)
            is PlaylistCovers.Result.Failed -> withContext(Dispatchers.Main) { Overlays.toast(result.reason, "error") }
        }
    }
}

/** Sets (or, with null, clears) Liked Songs' picture and drops the old file. */
fun setLikedSongsCover(cover: String?) {
    AppSettings.update { it.copy(likedSongsCover = cover) }
    PlaylistCovers.prune(Library.state.value.localPlaylists)
}

/** Liked Songs' picture: the one you chose, or the phone's heart tile. */
@Composable
fun LikedSongsArt(size: Dp, shape: Shape) {
    val chosen = AppSettings.state.collectAsState().value.likedSongsCover
    val cover = PlaylistCovers.url(chosen)
    if (cover != null) {
        Artwork(cover, Modifier.size(size), size = 600, shape = shape)
    } else {
        GradientTile("favorite", size, LikedGradient, shape)
    }
}

/** A cover that shows a pencil and "Choose photo" under the pointer and opens the picker on click, as Spotify's does. */
@Composable
fun EditableCover(size: Dp, shape: Shape, onChoose: () -> Unit, art: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onChoose),
    ) {
        art()
        AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ConvxIcon("edit", tint = Color.White, size = size * 0.22f)
                Text("Choose photo", style = ConvxType.SubheadStrong, color = Color.White)
            }
        }
    }
}

/**
 * A local playlist's own picture. Without one, the CD cover Convx draws from its songs (or, with
 * that off in Settings, the mosaic of its first covers, which also stands in while the CD is drawn).
 */
@Composable
fun PlaylistCoverArt(playlist: LocalPlaylist, size: Dp, shape: Shape) {
    val cover = PlaylistCovers.url(playlist.cover)
    val discs = AppSettings.state.collectAsState().value.discCovers
    if (cover != null) {
        Artwork(cover, Modifier.size(size), size = 600, shape = shape)
        return
    }
    val disc = if (discs) {
        val key = remember(playlist.name, playlist.coverSeed, playlist.songs) { DiscCovers.key(playlist) }
        produceState(DiscCovers.peek(playlist, key), key) { value = DiscCovers.cover(playlist) }.value
    } else {
        null
    }
    if (disc != null) {
        Artwork(disc, Modifier.size(size), size = 600, shape = shape)
    } else {
        val thumbs = remember(playlist.songs) { playlist.songs.mapNotNull { it.thumbnail }.distinct().take(4) }
        MosaicArt(thumbs, size, shape)
    }
}

/**
 * The playlist page's cover, as Spotify's: the pointer over it shows a pencil and "Choose photo",
 * and a click opens the picker.
 */
@Composable
fun EditablePlaylistCover(playlist: LocalPlaylist, size: Dp, shape: Shape) {
    EditableCover(size, shape, onChoose = { choosePlaylistCover(playlist.id) }) { PlaylistCoverArt(playlist, size, shape) }
}
