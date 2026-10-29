/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.tools

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.convx.desktop.core.Log
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.PlayerIcons
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSpeedDial
import com.convx.desktop.data.toSong
import com.convx.desktop.data.PlayerBackground
import com.convx.desktop.data.PlayerDesign
import com.convx.desktop.data.PlayerLook
import com.convx.desktop.data.SeekBarStyle
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.ResolvedStream
import com.convx.desktop.ui.shell.AppShell
import com.convx.desktop.ui.shell.ExploreAnchor
import com.convx.desktop.ui.screens.settings.SettingsHubs
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.Navigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.Section
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
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the main screens to PNG files without a window, with live YouTube Music data,
 * so the look can be checked on a build machine. Usage: `Snapshots <output dir>`.
 */
@OptIn(ExperimentalComposeUiApi::class)
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

    AppSettings.update { it.copy(rightPanel = null, sidebarCollapsed = false) }
    com.convx.desktop.ui.theme.AppFonts.start()
    val navigator = Navigator()
    val scene = ImageComposeScene(1440, 900, Density(1f)) { AppShell(navigator) }

    // Frame times only ever move forward, whether a frame came from the wall clock or a film.
    var clock = System.nanoTime()
    fun render(at: Long = System.nanoTime()): org.jetbrains.skia.Image {
        clock = maxOf(at, clock + 1)
        return scene.render(clock)
    }

    fun save(name: String, image: org.jetbrains.skia.Image) {
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        if (png == null) {
            Log.w("Snapshots", "Couldn't encode $name")
            return
        }
        File(out, "$name.png").writeBytes(png)
        Log.i("Snapshots", "Wrote $name.png")
    }

    // A shot that never finishes rendering is a hang: print where the render thread is stuck and
    // stop, instead of sitting until the build's timeout with nothing in the log.
    val renderThread = Thread.currentThread()
    var shotName = ""
    var shotDeadline = Long.MAX_VALUE
    Thread({
        while (true) {
            Thread.sleep(5_000)
            if (System.nanoTime() > shotDeadline) {
                System.err.println("Snapshot $shotName hung. Render thread:")
                renderThread.stackTrace.take(80).forEach { System.err.println("    at $it") }
                Thread.getAllStackTraces().filterKeys { it !== renderThread && !it.isDaemon }.forEach { (thread, stack) ->
                    System.err.println("Thread ${thread.name}:")
                    stack.take(25).forEach { System.err.println("    at $it") }
                }
                System.err.flush()
                Runtime.getRuntime().halt(3)
            }
        }
    }, "snapshot-watchdog").apply { isDaemon = true }.start()

    fun shoot(name: String, seconds: Int = 10, setup: () -> Unit = {}) {
        shotName = name
        shotDeadline = System.nanoTime() + (seconds + 60) * 1_000_000_000L
        setup()
        val end = System.nanoTime() + seconds * 1_000_000_000L
        var image = render()
        while (System.nanoTime() < end) {
            Snapshot.sendApplyNotifications()
            Thread.sleep(40)
            image = render()
        }
        save(name, image)
        shotDeadline = Long.MAX_VALUE
    }

    // An animation caught at fixed points of its own time, at 60 frames a second, so a slow
    // first frame on the build machine doesn't decide what gets captured.
    fun film(name: String, atMs: List<Long>, setup: () -> Unit) {
        shotName = name
        shotDeadline = System.nanoTime() + (atMs.maxOrNull() ?: 0L) * 1_000_000L + 60_000_000_000L
        setup()
        Snapshot.sendApplyNotifications()
        val start = maxOf(System.nanoTime(), clock + 1)
        render(start)
        var elapsed = 0L
        for (at in atMs) {
            var image = render(start + elapsed * 1_000_000L)
            while (elapsed < at) {
                elapsed += 16
                Snapshot.sendApplyNotifications()
                image = render(start + elapsed * 1_000_000L)
            }
            save("$name-${at}ms", image)
        }
        // Let the wall clock catch up, so the next shot's frames don't go back in time.
        while (System.nanoTime() < clock) Thread.sleep(5)
        shotDeadline = Long.MAX_VALUE
    }

    runCatching {
        shoot("01-home", seconds = 20)
        // Home's strip unfolding out of its button, then settled with the feed's mood chips.
        film("01a-home-strip", listOf(60L, 140L, 240L, 470L)) { UiState.openSection(Section.Home, navigator) }
        shoot("01b-home-strip", seconds = 3)
        film("01c-home-strip-folding", listOf(120L, 300L)) { UiState.openSection(Section.Home, navigator) }
        shoot("01d-home", seconds = 2)
        shoot("02-search") { navigator.navigate(Screen.Search) }
        shoot("03-search-results") { navigator.navigate(Screen.SearchResults("the weeknd")) }
        album?.let { shoot("04-album") { navigator.navigate(Screen.Album(it.browseId)) } }
        artist?.let { shoot("05-artist") { navigator.navigate(Screen.Artist(it.id)) } }
        playlist?.let { shoot("06-playlist") { navigator.navigate(Screen.Playlist(it.id)) } }
        shoot("07-settings", seconds = 4) { navigator.navigate(Screen.Settings) }
        shoot("07b-settings-accounts", seconds = 2) { SettingsNav.openHub(SettingsHubs.accounts.id) }
        shoot("07c-settings-discord", seconds = 3) {
            AppSettings.update { it.copy(discordPresence = true, discordAdvanced = true) }
            SettingsNav.push(SettingsPage.Discord)
        }
        AppSettings.update { it.copy(discordPresence = false) }
        shoot("07d-settings-glass", seconds = 3) { SettingsNav.push(SettingsPage.LiquidGlass) }
        shoot("07da-settings-appearance", seconds = 2) { SettingsNav.openHub(SettingsHubs.appearance.id) }
        shoot("07db-settings-font", seconds = 2) { SettingsNav.push(SettingsPage.Font) }
        // The whole app in another of the phone's fonts, then back to its default.
        shoot("07dc-settings-font-outfit", seconds = 2) { AppSettings.update { it.copy(appFont = "outfit") } }
        AppSettings.update { it.copy(appFont = com.convx.desktop.ui.theme.AppFont.Default.id) }
        shoot("07dd-settings-layout-home", seconds = 2) {
            SettingsNav.back()
            SettingsNav.push(SettingsPage.LayoutHome)
        }
        shoot("07de-settings-home-order", seconds = 2) {
            AppSettings.update { it.copy(homeSectionsHidden = listOf("forgotten_favorites")) }
            SettingsNav.push(SettingsPage.HomeFeedOrder)
        }
        AppSettings.update { it.copy(homeSectionsHidden = emptyList()) }
        shoot("07e-settings-playback", seconds = 2) { SettingsNav.openHub(SettingsHubs.playback.id) }
        shoot("07f-settings-eq", seconds = 3) {
            AppSettings.update { it.copy(equalizer = it.equalizer.copy(enabled = true)) }
            SettingsNav.push(SettingsPage.Equalizer)
        }
        shoot("07g-settings-eq-advanced", seconds = 2) {
            AppSettings.update { it.copy(equalizer = it.equalizer.copy(advanced = true)) }
        }
        AppSettings.update { it.copy(equalizer = com.convx.desktop.audio.EqualizerSettings()) }
        shoot("07h-settings-lastfm", seconds = 2) { SettingsNav.push(SettingsPage.LastFm) }
        shoot("07i-settings-youtube", seconds = 2) { SettingsNav.push(SettingsPage.YouTubeMusic) }
        shoot("07j-apple-import", seconds = 2) { com.convx.desktop.ui.screens.settings.appleMusicImportDialog() }
        com.convx.desktop.ui.components.Overlays.dismissDialog()
        shoot("07k-settings-folders", seconds = 2) { SettingsNav.push(SettingsPage.LocalFolders) }
        shoot("07l-settings-network", seconds = 2) { SettingsNav.push(SettingsPage.Network) }
        shoot("07la-settings-privacy-hub", seconds = 2) { SettingsNav.openHub(SettingsHubs.privacy.id) }
        shoot("07lb-settings-backup", seconds = 2) { SettingsNav.push(SettingsPage.Backup) }
        shoot("07ld-settings-privacy", seconds = 2) { SettingsNav.push(SettingsPage.Privacy) }
        shoot("07le-settings-storage", seconds = 4) { SettingsNav.push(SettingsPage.Storage) }
        val csv = java.io.File.createTempFile("Road trip", ".csv").apply {
            writeText(
                "Track URI,Track Name,Artist Name(s),Album Name,Duration (ms)\n" +
                    "spotify:track:1,Bohemian Rhapsody,Queen,A Night at the Opera,354320\n" +
                    "spotify:track:2,Get Lucky,\"Daft Punk,Pharrell Williams\",Random Access Memories,369626\n",
            )
            deleteOnExit()
        }
        shoot("07lc-csv-columns", seconds = 2) { com.convx.desktop.ui.screens.settings.csvImportDialog(csv) }
        com.convx.desktop.ui.components.Overlays.dismissDialog()
        shoot("07m-party", seconds = 4) { navigator.navigate(Screen.Party) }
        // A real party on the phone's server; the free server may take a while to wake.
        shoot("07n-party-in", seconds = 30) { com.convx.desktop.integrations.party.Party.create("Convx CI") }
        com.convx.desktop.integrations.party.Party.leave()
        shoot("08-explore", seconds = 12) { navigator.navigate(Screen.Explore) }
        shoot("08a-explore-strip", seconds = 3) { UiState.openSection(Section.Explore, navigator) }
        shoot("08b-explore-jump", seconds = 3) { UiState.exploreJump = ExploreAnchor.Moods }
        if (songs.isNotEmpty()) {
            shoot("09-home-playing") {
                PlayerController.cue(songs, 0, positionMs = 62_000)
                navigator.navigate(Screen.Home)
            }
            // The phone's Ask before replacing the queue, for a song that isn't in it.
            shoot("09a-queue-conflict", seconds = 2) {
                AppSettings.update { it.copy(askBeforeReplacingQueue = true) }
                PlayerController.playSongs(listOf(com.convx.desktop.data.Song("dQw4w9WgXcQ", "Never Gonna Give You Up")))
            }
            com.convx.desktop.ui.components.Overlays.dismissDialog()
            AppSettings.update { it.copy(askBeforeReplacingQueue = false) }
            artist?.let {
                shoot("10-artist-playing", seconds = 6) { navigator.navigate(Screen.Artist(it.id)) }
                film("11-library-strip", listOf(100L, 220L)) { UiState.openSection(Section.Library, navigator) }
            }
            shoot("12-full-player-lyrics", seconds = 12) {
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
                UiState.fullPlayer = true
            }
            shoot("13-full-player-queue", seconds = 4) { UiState.fullPlayerMode = FullPlayerMode.Queue }
            // The phone's other player designs, each with one of its backgrounds and seek bars.
            fun design(transform: (com.convx.desktop.data.Settings) -> com.convx.desktop.data.Settings) = AppSettings.update(transform)
            shoot("13a-player-v2", seconds = 4) { UiState.fullPlayerMode = FullPlayerMode.Artwork }
            shoot("13b-player-card-glow", seconds = 4) {
                design { it.copy(playerDesign = PlayerDesign.Card, playerBackground = PlayerBackground.Glow, seekBarStyle = SeekBarStyle.Wavy) }
            }
            shoot("13c-player-vinyl-blur", seconds = 4) {
                design { it.copy(playerDesign = PlayerDesign.Vinyl, playerBackground = PlayerBackground.Blur, seekBarStyle = SeekBarStyle.Waveform, artworkShadow = true) }
            }
            shoot("13d-player-clover-mesh", seconds = 4) {
                design { it.copy(playerDesign = PlayerDesign.Clover, playerBackground = PlayerBackground.ColourMesh, seekBarStyle = SeekBarStyle.Slim, showUpNext = true) }
            }
            shoot("13e-player-apple", seconds = 4) { design { it.copy(playerDesign = PlayerDesign.AppleMusic, showUpNext = false) } }
            shoot("13f-player-v2-hero-lyrics", seconds = 6) {
                design { it.copy(playerDesign = PlayerDesign.AppleMusicV2, playerLook = PlayerLook.Hero) }
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
            }
            design {
                it.copy(
                    playerDesign = PlayerDesign.AppleMusicV2,
                    playerLook = PlayerLook.Squared,
                    playerBackground = PlayerBackground.Gradient,
                    seekBarStyle = SeekBarStyle.Default,
                    artworkShadow = false,
                )
            }
            // A few of the phone's lyrics styles, in the full player.
            fun lyrics(style: com.convx.desktop.data.LyricsStyle, position: com.convx.desktop.data.LyricsPosition = com.convx.desktop.data.LyricsPosition.Left, transform: (com.convx.desktop.data.Settings) -> com.convx.desktop.data.Settings = { it }) =
                AppSettings.update { transform(it.copy(lyricsStyle = style, lyricsPosition = position)) }
            shoot("13g-lyrics-apple-center", seconds = 4) {
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
                lyrics(com.convx.desktop.data.LyricsStyle.Apple, com.convx.desktop.data.LyricsPosition.Center)
            }
            shoot("13h-lyrics-karaoke-apple", seconds = 4) { lyrics(com.convx.desktop.data.LyricsStyle.KaraokeApple) }
            shoot("13i-lyrics-v2-fluid", seconds = 4) { lyrics(com.convx.desktop.data.LyricsStyle.LyricsV2) }
            shoot("13j-lyrics-metro-blur-right", seconds = 4) {
                lyrics(com.convx.desktop.data.LyricsStyle.Metro, com.convx.desktop.data.LyricsPosition.Right) { it.copy(lyricsStandardBlur = true) }
            }
            shoot("13k-lyrics-chord-serif", seconds = 4) {
                lyrics(com.convx.desktop.data.LyricsStyle.ChordSync) { it.copy(lyricsStandardBlur = false, lyricsFont = "serif", lyricsTextSize = 40f) }
            }
            lyrics(com.convx.desktop.data.LyricsStyle.Flamingo) { it.copy(lyricsFont = "default", lyricsTextSize = 34f) }

            // The lyrics menu: a translation kept for the song (stand-in lines, as CI has no key),
            // the menu from its button by the lyrics, the offset sheet, and a lyrics search.
            val playing = songs.first()
            val found = (com.convx.desktop.lyrics.LyricsService.state(playing).value as? com.convx.desktop.lyrics.LyricsState.Ready)?.lyrics
            if (found != null) {
                val count = found.lines.count { it.text.isNotBlank() }
                com.convx.desktop.lyrics.LyricsTweaks.update(playing.id) {
                    it.copy(translation = com.convx.desktop.lyrics.SavedTranslation(List(count) { i -> "Línea traducida ${i + 1}" }, "es", "Literal", com.convx.desktop.lyrics.LyricsTranslator.key(found)))
                }
                shoot("13l-lyrics-translated", seconds = 4) { AppSettings.update { it.copy(translateLanguage = "es") } }
            }
            shoot("13m-lyrics-menu", seconds = 3) {
                val at = Offset(1284f, 40f)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            Offset(700f, 40f).let { at ->
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            shoot("13n-lyrics-offset", seconds = 2) {
                com.convx.desktop.lyrics.LyricsTweaks.update(playing.id) { it.copy(offsetMs = 250) }
                com.convx.desktop.ui.player.editOffset(playing)
            }
            com.convx.desktop.ui.components.Overlays.dismissDialog()
            shoot("13o-lyrics-search", seconds = 10) { com.convx.desktop.ui.player.searchLyrics(playing, searchNow = true) }
            com.convx.desktop.ui.components.Overlays.dismissDialog()
            com.convx.desktop.lyrics.LyricsTweaks.update(playing.id) { it.copy(translation = null, offsetMs = 0) }
            AppSettings.update { it.copy(translateLanguage = "en") }

            // Controls & layout in the player: the Convx mark for the cover, and play/pause on it by the lyrics.
            shoot("13p-player-hidden-artwork", seconds = 3) {
                AppSettings.update { it.copy(hidePlayerArtwork = true, lyricsArtworkPlayPause = true) }
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
            }
            AppSettings.update { it.copy(hidePlayerArtwork = false, lyricsArtworkPlayPause = false) }

            // A lived-in library, for the side bar, the library page and the song-list pages.
            UiState.fullPlayer = false
            songs.take(6).forEach { Library.toggleLike(it) }
            // Two months of plays with the time listened, for Stats and Home's most played.
            val now = System.currentTimeMillis()
            Library.applySync { data ->
                data.copy(
                    history = songs.take(8).flatMapIndexed { i, song ->
                        List(8 - i) { n ->
                            com.convx.desktop.data.PlayEvent(song, now - (n * 7L + i) * 86_400_000L - 3_600_000L, playedMs = (song.duration ?: 200) * 1000L * (8 - n) / 8)
                        }
                    }.sortedByDescending { it.at },
                )
            }
            album?.let { Library.toggleAlbum(it.toSaved()) }
            artist?.let { Library.toggleArtist(it.toSaved()) }
            playlist?.let { Library.togglePlaylist(it.toSaved()) }
            val mix = Library.createPlaylist("Late night drive", songs.take(8))
            // Three songs downloaded and two waiting, for the Downloaded page and the list buttons.
            songs.take(3).forEach { song ->
                val file = File(Downloads.dir, "${song.id}.m4a").apply { writeBytes(ByteArray(3_500_000)) }
                Downloads.record(song, file, ResolvedStream(song.id, "", "audio/mp4", 128_000, 44_100, file.length(), null, null, "Snapshots", 0))
            }
            Downloads.download(songs.drop(3).take(2))
            // Home as the phone builds it from your listening: the featured card, Speed dial with
            // a pin, Quick picks and the rest, in the default order so the shots compare.
            album?.let { Library.togglePin(it.toSpeedDial()) }
            AppSettings.update { it.copy(homeRandomOrder = false) }
            com.convx.desktop.ui.screens.HomeFeed.load(force = true)
            fun scroll(lines: Float) = scene.sendPointerEvent(PointerEventType.Scroll, Offset(900f, 500f), scrollDelta = Offset(0f, lines))
            shoot("20-home-feed", seconds = 25) { navigator.navigate(Screen.Home) }
            shoot("20a-home-feed-down", seconds = 3) { scroll(10f) }
            shoot("20b-home-feed-further", seconds = 3) { scroll(10f) }
            shoot("21-stats", seconds = 8) { navigator.navigate(Screen.Stats) }
            shoot("21a-stats-months", seconds = 3) {
                com.convx.desktop.ui.screens.StatsSelection.option = com.convx.desktop.ui.screens.StatsOption.Months
                com.convx.desktop.ui.screens.StatsSelection.chip = 1
            }
            com.convx.desktop.ui.screens.StatsSelection.option = com.convx.desktop.ui.screens.StatsOption.Continuous
            com.convx.desktop.ui.screens.StatsSelection.chip = 0
            shoot("22-my-top", seconds = 4) { navigator.navigate(Screen.MyTop) }
            AppSettings.update { it.copy(homeRandomOrder = true) }
            shoot("14-library", seconds = 8) { navigator.navigate(Screen.Library) }
            shoot("15-liked", seconds = 6) { navigator.navigate(Screen.Liked) }
            shoot("16-local-playlist", seconds = 6) { navigator.navigate(Screen.LocalPlaylist(mix)) }
            shoot("16b-downloads", seconds = 4) { navigator.navigate(Screen.Downloads) }
            shoot("17-sidebar-collapsed", seconds = 4) {
                AppSettings.update { it.copy(sidebarCollapsed = true) }
                navigator.navigate(Screen.Home)
            }
            // Right-click the now playing pill: the song menu, glass over the page.
            shoot("18-song-menu", seconds = 3) {
                val at = Offset(760f, 856f)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
            }
            AppSettings.update { it.copy(sidebarCollapsed = false) }
            // A click on the empty top bar closes the menu.
            Offset(1200f, 30f).let { at ->
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            shoot("19-settings-player-design", seconds = 6) {
                navigator.navigate(Screen.Settings)
                SettingsNav.push(SettingsPage.PlayerDesign)
            }
            shoot("19a-settings-player-hub", seconds = 2) { SettingsNav.openHub(com.convx.desktop.ui.screens.settings.SettingsHubs.player.id) }
            shoot("19b-settings-lyrics-style", seconds = 5) { SettingsNav.push(SettingsPage.LyricsStyle) }
            shoot("19c-settings-lyrics-effects", seconds = 3) { SettingsNav.push(SettingsPage.LyricsEffects) }
            shoot("19d-settings-lyrics-text", seconds = 2) { SettingsNav.push(SettingsPage.LyricsText) }
            shoot("19e-settings-lyrics-sources", seconds = 2) {
                SettingsNav.back()
                SettingsNav.back()
                SettingsNav.back()
                SettingsNav.push(SettingsPage.LyricsSources)
            }
            shoot("19f-settings-romanization", seconds = 2) {
                SettingsNav.back()
                SettingsNav.push(SettingsPage.Romanization)
            }
            shoot("19g-settings-ai-translation", seconds = 2) {
                SettingsNav.back()
                SettingsNav.push(SettingsPage.AiTranslation)
            }
            shoot("19h-settings-library-hub", seconds = 2) { SettingsNav.openHub(com.convx.desktop.ui.screens.settings.SettingsHubs.library.id) }
            shoot("19i-settings-controls-layout", seconds = 3) { SettingsNav.push(SettingsPage.ControlsLayout) }
            // The phone's settings popup, from the account button once it's switched on.
            AppSettings.update { it.copy(settingsPopup = true) }
            shoot("19j-settings-popup", seconds = 3) {
                Snapshot.sendApplyNotifications()
                render()
                val at = Offset(1406f, 32f)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            Offset(1200f, 30f).let { at ->
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            // Four columns and plain icons in Your Library, two of the phone's grid and library choices.
            AppSettings.update { it.copy(settingsPopup = false, gridColumns = 4, libraryIconsOnly = true) }
            shoot("14b-library-four-columns", seconds = 4) { navigator.navigate(Screen.Library) }
            AppSettings.update { it.copy(gridColumns = 0, libraryIconsOnly = false) }
            // Custom player icons: a big PNG (scaled down on the way in) kept in its own colours for
            // play, and an SVG star tinted like the stock glyph for v2's heart.
            val png = File.createTempFile("convx-icon", ".png").apply {
                val surface = Surface.makeRasterN32Premul(1024, 1024)
                surface.canvas.drawCircle(512f, 512f, 500f, Paint().apply { color = 0xFFFF9500.toInt() })
                surface.canvas.drawCircle(512f, 512f, 220f, Paint().apply { color = 0xFF0A84FF.toInt() })
                writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            val svg = File.createTempFile("convx-icon", ".svg").apply {
                writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M12 2l3 7h7l-5.5 4.5 2 7.5-6.5-4.5-6.5 4.5 2-7.5L2 9h7z" fill="#000"/></svg>""")
            }
            runCatching {
                PlayerIcons.import(PlayerIconSlot.PLAY, png)
                PlayerIcons.import(PlayerIconSlot.V2_PLAY, png)
                PlayerIcons.import(PlayerIconSlot.V2_LIKE, svg)
                PlayerIcons.setTint(PlayerIconSlot.V2_LIKE, true)
            }.onFailure { Log.e("Snapshots", "Player icon import failed", it) }
            shoot("19k-settings-player-icons", seconds = 3) {
                navigator.navigate(Screen.Settings)
                SettingsNav.push(SettingsPage.CustomPlayerIcons)
            }
            shoot("19l-player-custom-icons", seconds = 3) {
                UiState.fullPlayerMode = FullPlayerMode.Artwork
                UiState.fullPlayer = true
            }
            UiState.fullPlayer = false
            PlayerIconSlot.entries.forEach { PlayerIcons.clear(it) }
            // Last, as the bottom of Home has hung the run before: the watchdog shows where.
            shoot("20c-home-feed-end", seconds = 6) {
                navigator.navigate(Screen.Home)
                Snapshot.sendApplyNotifications()
                render()
                scroll(40f)
            }
        }
    }.onFailure { Log.e("Snapshots", "Rendering failed", it) }

    scene.close()
    PlayerController.shutdown()
    exitProcess(0)
}
