/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.tools

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.convx.desktop.core.Log
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.AppShell
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.Navigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.SidePanel
import com.convx.desktop.ui.shell.UiState
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the main screens to PNG files without a window, with live YouTube Music data,
 * so the look can be checked on a build machine. Usage: `Snapshots <output dir>`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/snapshots").apply { mkdirs() }
    runBlocking(Dispatchers.IO) { initYouTube() }

    suspend fun first(query: String, filter: SearchFilter) =
        YouTube.search(query, filter).onFailure { Log.w("Snapshots", "Search \"$query\" failed", it) }.getOrNull()?.items.orEmpty()

    val songs = runBlocking(Dispatchers.IO) { first("blinding lights the weeknd", SearchFilter.FILTER_SONG) }
        .filterIsInstance<SongItem>().map { it.toSong() }
    val album = runBlocking(Dispatchers.IO) { first("after hours the weeknd", SearchFilter.FILTER_ALBUM) }
        .filterIsInstance<AlbumItem>().firstOrNull()
    val artist = runBlocking(Dispatchers.IO) { first("the weeknd", SearchFilter.FILTER_ARTIST) }
        .filterIsInstance<ArtistItem>().firstOrNull()
    val playlist = runBlocking(Dispatchers.IO) { first("today's top hits", SearchFilter.FILTER_FEATURED_PLAYLIST) }
        .filterIsInstance<PlaylistItem>().firstOrNull()

    AppSettings.update { it.copy(rightPanel = null) }
    val navigator = Navigator()
    val scene = ImageComposeScene(1440, 900, Density(1f)) { AppShell(navigator) }

    fun shoot(name: String, seconds: Int = 10, setup: () -> Unit = {}) {
        setup()
        val end = System.nanoTime() + seconds * 1_000_000_000L
        var image = scene.render(System.nanoTime())
        while (System.nanoTime() < end) {
            Snapshot.sendApplyNotifications()
            Thread.sleep(40)
            image = scene.render(System.nanoTime())
        }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        if (png == null) {
            Log.w("Snapshots", "Couldn't encode $name")
            return
        }
        File(out, "$name.png").writeBytes(png)
        Log.i("Snapshots", "Wrote $name.png")
    }

    runCatching {
        shoot("01-home", seconds = 20)
        shoot("02-search") { navigator.navigate(Screen.Search) }
        shoot("03-search-results") { navigator.navigate(Screen.SearchResults("the weeknd")) }
        album?.let { shoot("04-album") { navigator.navigate(Screen.Album(it.browseId)) } }
        artist?.let { shoot("05-artist") { navigator.navigate(Screen.Artist(it.id)) } }
        playlist?.let { shoot("06-playlist") { navigator.navigate(Screen.Playlist(it.id)) } }
        shoot("07-settings", seconds = 4) { navigator.navigate(Screen.Settings) }
        if (songs.isNotEmpty()) {
            shoot("08-now-playing-panel") {
                PlayerController.cue(songs, 0, positionMs = 62_000)
                AppSettings.update { it.copy(rightPanel = SidePanel.NowPlaying.key) }
                navigator.navigate(Screen.Home)
            }
            shoot("09-lyrics-panel") { AppSettings.update { it.copy(rightPanel = SidePanel.Lyrics.key) } }
            shoot("10-queue-panel", seconds = 4) { AppSettings.update { it.copy(rightPanel = SidePanel.Queue.key) } }
            shoot("11-full-player-lyrics", seconds = 12) {
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
                UiState.fullPlayer = true
            }
            shoot("12-full-player-queue", seconds = 4) { UiState.fullPlayerMode = FullPlayerMode.Queue }
        }
    }.onFailure { Log.e("Snapshots", "Rendering failed", it) }

    scene.close()
    PlayerController.shutdown()
    exitProcess(0)
}
