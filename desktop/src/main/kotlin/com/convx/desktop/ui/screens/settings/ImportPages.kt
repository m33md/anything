/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.PlaylistImport
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The phone's Spotify import, on the Spotify page once you're connected. */
@Composable
internal fun SpotifyImportSection() {
    val state by PlaylistImport.state.collectAsState()
    SettingsSection(
        "Import",
        footer = "Import your Spotify playlists and Liked Songs into your library. Convx searches YouTube Music for the best " +
            "match of each song and mirrors them to Convx playlists. Importing a playlist again updates it.",
    ) {
        SettingsRow(
            "Import playlists",
            importStatus(state, "Spotify") ?: "Choose which playlists to copy into Convx",
            icon = "playlist_add",
            tint = SystemColors.Green,
            enabled = !PlaylistImport.busy,
            onClick = { spotifyImportDialog() },
        ) { Chevron() }
        if (state is PlaylistImport.State.Matching || state is PlaylistImport.State.Loading) {
            RowDivider()
            ButtonRow("Stop importing", color = SystemColors.Red) { PlaylistImport.cancel() }
        }
    }
}

/** A one-line account of an import from [source], or null when there's nothing to say. */
private fun importStatus(state: PlaylistImport.State, source: String): String? = when (state) {
    is PlaylistImport.State.Loading -> if (state.source == source) "Loading from $source…" else null
    is PlaylistImport.State.Matching -> if (state.source == source) {
        val list = if (state.lists > 1) " (${state.list} of ${state.lists})" else ""
        "Matching ${state.title}$list: ${state.done} of ${state.total} songs"
    } else null
    is PlaylistImport.State.Done -> state.message
    is PlaylistImport.State.Failed -> state.message
    else -> null
}

@Composable
private fun Chevron() = ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp)

/** The phone's SpotifyPlaylistBottomSheet: Liked Songs and every playlist, with Select All. */
private fun spotifyImportDialog() {
    Overlays.showDialog {
        var playlists by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
        var liked by remember { mutableStateOf<Int?>(null) }
        var failed by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        LaunchedEffect(Unit) {
            val result = SpotifyApi.playlists()
            playlists = result.getOrNull()
            failed = result.isFailure
            liked = SpotifyApi.likedSongs().getOrNull()?.size
        }
        val ids = listOf(LikedKey) + playlists.orEmpty().map { it.id }
        DialogCard("Select playlists to import") {
            when {
                failed -> Text("Couldn't load your Spotify playlists. Check your connection and try again.", style = ConvxType.Body, color = SystemColors.Red)
                playlists == null -> Text("Loading your playlists…", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                else -> {
                    Text("${playlists!!.size} playlists available", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        CheckRow("Select All", checked = selected.size == ids.size) {
                            selected = if (selected.size == ids.size) emptySet() else ids.toSet()
                        }
                        RowDivider(PlainInset)
                        CheckRow(
                            "Liked Songs",
                            "Your Spotify Liked Songs" + (liked?.let { " · $it songs" } ?: ""),
                            checked = LikedKey in selected,
                            icon = "favorite",
                            tint = SystemColors.Indigo,
                        ) { selected = selected.toggle(LikedKey) }
                        playlists!!.forEach { playlist ->
                            RowDivider()
                            CheckRow(
                                playlist.name,
                                "${playlist.trackCount} songs · ${playlist.owner}",
                                checked = playlist.id in selected,
                                icon = "queue_music",
                                tint = SystemColors.Green,
                            ) { selected = selected.toggle(playlist.id) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton(if (selected.isEmpty()) "Import" else "Import ${selected.size}", {
                    if (selected.isNotEmpty()) {
                        val sources = buildList {
                            if (LikedKey in selected) add(PlaylistImport.SpotifySource(null))
                            playlists.orEmpty().filter { it.id in selected }.forEach { add(PlaylistImport.SpotifySource(it)) }
                        }
                        PlaylistImport.importSpotify(sources)
                        Overlays.dismissDialog()
                    }
                }, filled = selected.isNotEmpty())
            }
        }
    }
}

private const val LikedKey = "\u0000liked"

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** The phone's Apple Music import dialog: a public link or a Music/iTunes XML export. */
internal fun appleMusicImportDialog() {
    if (!PlaylistImport.busy && PlaylistImport.state.value !is PlaylistImport.State.Ready) PlaylistImport.dismiss()
    Overlays.showDialog {
        val state by PlaylistImport.state.collectAsState()
        val navigator = LocalNavigator.current
        var url by remember { mutableStateOf("") }
        var choosing by remember { mutableStateOf(false) }
        val library by Library.state.collectAsState()
        DialogCard("Import from Apple Music") {
            when (val s = state) {
                is PlaylistImport.State.Loading, is PlaylistImport.State.Matching -> {
                    Text(
                        if (s is PlaylistImport.State.Matching) "Matching tracks: ${s.done} of ${s.total}" else "Loading playlist…",
                        style = ConvxType.Body,
                        color = ConvxColors.Label,
                    )
                    if (s is PlaylistImport.State.Matching && s.source != "Apple Music") {
                        Spacer(Modifier.height(6.dp))
                        Text("A Spotify import is running; wait for it to finish.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
                    }
                    Spacer(Modifier.height(20.dp))
                    DialogButtons {
                        PillButton("Hide", { Overlays.dismissDialog() })
                        PillButton("Cancel", { PlaylistImport.cancel() })
                    }
                }
                is PlaylistImport.State.Ready -> {
                    Text(s.title, style = ConvxType.Headline, color = ConvxColors.Label)
                    Text("${s.songs.size} tracks matched, ${s.skipped} skipped.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
                    if (choosing) {
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            library.localPlaylists.forEachIndexed { index, playlist ->
                                if (index > 0) RowDivider()
                                SettingsRow(playlist.name, "${playlist.songs.size} songs", icon = "queue_music", tint = SystemColors.Red, onClick = {
                                    PlaylistImport.saveApple(playlist.id)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    DialogButtons {
                        PillButton("Cancel", {
                            PlaylistImport.dismiss()
                            PlaylistImport.cancel()
                            Overlays.dismissDialog()
                        })
                        if (library.localPlaylists.isNotEmpty() && !choosing) PillButton("Choose playlist", { choosing = true })
                        PillButton("Create playlist", { PlaylistImport.saveApple(null) }, filled = true)
                    }
                }
                is PlaylistImport.State.Done -> {
                    Text(s.message, style = ConvxType.Body, color = ConvxColors.Label)
                    Spacer(Modifier.height(20.dp))
                    DialogButtons {
                        PillButton("Done", {
                            PlaylistImport.dismiss()
                            Overlays.dismissDialog()
                        })
                        s.playlistId?.let { id ->
                            PillButton("Open playlist", {
                                PlaylistImport.dismiss()
                                Overlays.dismissDialog()
                                navigator.navigate(Screen.LocalPlaylist(id))
                            }, filled = true)
                        }
                    }
                }
                else -> {
                    Text(
                        "Paste a public Apple Music playlist link. Tracks will be matched to YouTube Music.",
                        style = ConvxType.Body,
                        color = ConvxColors.SecondaryLabel,
                    )
                    Spacer(Modifier.height(12.dp))
                    DialogField("Playlist link", url) { url = it }
                    (s as? PlaylistImport.State.Failed)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.message, style = ConvxType.Subhead, color = SystemColors.Red)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "For a private playlist, export it as XML from Music on Mac, or iTunes or Apple Music on Windows " +
                            "(File, Library, Export Playlist), then import the file here.",
                        style = ConvxType.Subhead,
                        color = ConvxColors.SecondaryLabel,
                    )
                    Spacer(Modifier.height(20.dp))
                    DialogButtons {
                        PillButton("Import playlist XML", { pickExport()?.let { PlaylistImport.loadAppleExport(it) } }, icon = "playlist_add")
                        PillButton("Cancel", {
                            PlaylistImport.dismiss()
                            Overlays.dismissDialog()
                        })
                        PillButton("Import playlist", { if (url.isNotBlank()) PlaylistImport.loadAppleLink(url) }, filled = url.isNotBlank())
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { content() }
}

private fun pickExport(): File? {
    val picker = FileDialog(null as Frame?, "Import an Apple Music playlist", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".xml", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true) }
        file = "*.xml"
        isVisible = true
    }
    val name = picker.file ?: return null
    return File(picker.directory, name)
}
