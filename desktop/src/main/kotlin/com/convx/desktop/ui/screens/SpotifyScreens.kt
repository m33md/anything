/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.formatTotal
import com.convx.desktop.core.pluralize
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.integrations.spotify.SpotifyTrack
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState

/** Spotify's green, for the little marks that say where a list came from. */
val SpotifyGreen = Color(0xFF1ED760)

/** One of your Spotify playlists, read from Spotify and played from YouTube Music. */
@Composable
fun SpotifyPlaylistScreen(id: String) {
    val page = rememberPage("spotify-playlist-$id") {
        val playlist = SpotifyApi.playlists().getOrThrow().firstOrNull { it.id == id }
        val tracks = SpotifyApi.playlistTracks(id).getOrThrow()
        playlist to tracks
    }
    val playlist = page.value?.first
    val tracks = page.value?.second
    SpotifyTrackPage(
        title = playlist?.name ?: "Spotify playlist",
        subtitle = playlist?.owner,
        description = playlist?.description?.takeIf { it.isNotBlank() }?.let(::stripTags),
        art = { size -> Artwork(playlist?.image, Modifier.size(size), size = 640, shape = RoundedCornerShape(12.dp), placeholderIcon = "spotify") },
        tracks = tracks,
        error = page.error,
        onRetry = {
            SpotifyApi.clearCache()
            page.retry()
        },
        keyPrefix = "spotify-$id",
    )
}

/** Your Liked Songs on Spotify. */
@Composable
fun SpotifyLikedScreen() {
    val page = rememberPage("spotify-liked") { SpotifyApi.likedSongs().getOrThrow() }
    SpotifyTrackPage(
        title = "Liked Songs",
        subtitle = "Spotify",
        description = null,
        art = { size -> GradientTile("favorite", size, listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)), RoundedCornerShape(12.dp)) },
        tracks = page.value,
        error = page.error,
        onRetry = {
            SpotifyApi.clearCache()
            page.retry()
        },
        keyPrefix = "spotify-liked",
    )
}

@Composable
private fun SpotifyTrackPage(
    title: String,
    subtitle: String?,
    description: String?,
    art: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
    tracks: List<SpotifyTrack>?,
    error: Throwable?,
    onRetry: () -> Unit,
    keyPrefix: String,
) {
    val playback = LocalPlayback.current
    // Songs swap their Spotify ids for YouTube Music ones as they're matched, so rows follow the matches.
    val matched by SpotifyMatcher.matched.collectAsState()
    val songs = remember(tracks, matched) { tracks.orEmpty().map(SpotifyMatcher::toSong) }
    val playingHere = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(state = rememberLazyListState(), tint = Color.Black, topPadding = 0.dp) {
            val seconds = songs.sumOf { (it.duration ?: 0).toLong() }
            collectionHeader(
                title = title,
                art = art,
                subtitle = subtitle,
                metadata = listOfNotNull(
                    "Spotify",
                    tracks?.let { pluralize(it.size, "song") },
                    if (seconds > 0) formatTotal(seconds) else null,
                ).joinToString(" · "),
                description = description,
                playing = playingHere,
                enabled = songs.isNotEmpty(),
                onPlay = {
                    if (playingHere) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = false)
                },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = true) },
                menu = { dismiss ->
                    MenuItem("Reload from Spotify", "refresh") {
                        dismiss()
                        onRetry()
                    }
                },
            )
            when {
                tracks != null && tracks.isEmpty() -> item {
                    MessageState("Nothing to play here", "This list has no songs Convx can match, like local files or podcasts.", icon = "spotify")
                }
                tracks != null -> songTable(songs, title, keyPrefix)
                error != null -> item {
                    MessageState("Couldn't load this from Spotify", friendlyError(error), icon = "spotify", actionText = "Try again", onAction = onRetry)
                }
                else -> item { LoadingState() }
            }
        }
    }
}

/** Spotify writes playlist descriptions as HTML. */
private fun stripTags(html: String): String = html
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#x27;", "'")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
