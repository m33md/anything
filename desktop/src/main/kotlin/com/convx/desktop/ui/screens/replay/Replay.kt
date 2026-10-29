/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.replay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AlbumRef
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.AudioEngine
import com.convx.desktop.player.CachedAudio
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.LocalMusic
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.StreamResolver
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.artworkUrl
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** A top artist with the picture the pages draw. */
class ReplayArtist(val ref: ArtistRef, val picture: String?, val playedMs: Long)

/**
 * A year of your listening, as the phone's Wrapped reads it from its database: the top 30 songs,
 * the top 5 artists and albums, how many of each you played and for how many minutes.
 */
data class ReplayData(
    val year: Int,
    val minutes: Long,
    val songs: List<Library.Ranked<Song>>,
    val artists: List<ReplayArtist>,
    val albums: List<Library.Ranked<AlbumRef>>,
    val songCount: Int,
    val artistCount: Int,
    val albumCount: Int,
    /** Everything played that year, most played first, for the Replay playlist. */
    val everySong: List<Song>,
    /** The song each page plays under it. */
    val cues: Map<ReplayPage, String>,
)

/** The Summary's playlist button: a year not added yet, one added before that can be brought up to date, and done. */
enum class ReplayPlaylist { Add, Update, Added }

/**
 * The phone's Wrapped, which it shows as Apple Music's Replay story: opened from Stats, over
 * everything else, until it's closed.
 */
object Replay {
    /** What the phone's Conclusion page plays instead of one of your songs. */
    internal const val ThemeVideo = "2-p9DM2Xvsc"

    var open by mutableStateOf(false)
        private set
    var data by mutableStateOf<ReplayData?>(null)
        private set
    var page by mutableIntStateOf(0)
        private set

    /** Held down with the mouse, as a finger holds the phone's. */
    var held by mutableStateOf(false)

    /** Stopped with Space, which a mouse has no other way to do for long. */
    var stopped by mutableStateOf(false)

    val paused: Boolean get() = held || stopped

    /** Silent to begin with, as on the phone. */
    var muted by mutableStateOf(true)
        private set
    var playlist by mutableStateOf(ReplayPlaylist.Add)
        private set

    private var loading: Job? = null

    /** Whether opening it paused your music, so closing it can play it again. */
    private var pausedMusic = false

    fun show() {
        val year = year()
        if (year == null) {
            Overlays.toast("Replay needs some listening first", "stats")
            return
        }
        page = 0
        muted = true
        held = false
        stopped = false
        data = null
        playlist = if (Library.playlist(playlistId(year)) != null) ReplayPlaylist.Update else ReplayPlaylist.Add
        open = true
        loading?.cancel()
        loading = AppScope.launch {
            val built = withContext(Dispatchers.IO) { build(year) }
            data = built
            ReplayAudio.cue(built.cues[ReplayPage.entries[page]])
            // The opening page's covers, fetched now so they're there when it cycles through them.
            launch(Dispatchers.IO) { built.songs.take(6).forEach { song -> artworkUrl(song.value.thumbnail, 544)?.let { ImageCache.load(it) } } }
            // Pictures of artists you don't follow come later, rather than holding the story up.
            val missing = built.artists.filter { it.picture == null }.mapNotNull { it.ref.id }
            if (missing.isNotEmpty()) {
                val found = withContext(Dispatchers.IO) { lookUpPictures(missing) }
                if (found.isNotEmpty()) data = built.copy(artists = built.artists.map { ReplayArtist(it.ref, it.picture ?: found[it.ref.id], it.playedMs) })
            }
        }
    }

    fun close() {
        if (!open) return
        open = false
        loading?.cancel()
        ReplayAudio.stop()
        if (pausedMusic && !PlayerController.state.value.isPlaying) PlayerController.play()
        pausedMusic = false
    }

    fun go(to: Int) {
        val next = to.coerceIn(0, ReplayPage.entries.lastIndex)
        if (next == page) return
        page = next
        data?.let { ReplayAudio.cue(it.cues[ReplayPage.entries[next]]) }
    }

    fun toggleMute() {
        muted = !muted
        // Two songs at once is noise, so yours waits until Replay is closed.
        if (!muted && PlayerController.state.value.isPlaying) {
            PlayerController.pause()
            pausedMusic = true
        }
        ReplayAudio.setMuted(muted)
    }

    /** The phone's "Add Replay Playlist": every song played that year, as a playlist of yours. */
    fun addPlaylist() {
        val replay = data ?: return
        if (playlist == ReplayPlaylist.Added) return
        // One playlist per year that a second add brings up to date, where the phone adds another.
        Library.upsertPlaylist(playlistId(replay.year), "Convx ${replay.year}", replay.everySong)
        playlist = ReplayPlaylist.Added
        Overlays.toast("Convx ${replay.year} is in Your Library", "playlist_add")
    }

    fun shareText(replay: ReplayData): String {
        val nf = java.text.NumberFormat.getIntegerInstance()
        return "My Replay: ${nf.format(replay.minutes)} minutes, ${nf.format(replay.songCount)} songs" +
            (replay.artists.firstOrNull()?.let { ", top artist ${it.ref.name}" } ?: "") +
            " — Convx"
    }

    private fun playlistId(year: Int) = "local-replay-$year"

    private fun bounds(year: Int): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 to
            LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    /**
     * This year, as on the phone. Early in January, before anything's been played, last year's
     * instead, where the phone would show a year of nothing.
     */
    private fun year(): Int? {
        val now = LocalDate.now().year
        return listOf(now, now - 1).firstOrNull { year ->
            val (from, to) = bounds(year)
            Library.value.history.any { it.at > from && it.at <= to }
        }
    }

    private fun build(year: Int): ReplayData {
        val (from, to) = bounds(year)
        val stats = Library.listeningStats(from, to, limit = Int.MAX_VALUE)
        val events = Library.value.history.filter { it.at > from && it.at <= to }
        val songs = stats.songs.take(30)
        val topArtists = stats.artists.take(5)
        val artists = topArtists.map { ReplayArtist(it.value, it.value.id?.let(Library::artistPicture), it.playedMs) }
        val albums = stats.albums.take(5)
        val everySong = stats.songs.map { it.value }
        return ReplayData(
            year = year,
            minutes = events.sumOf { it.playedMs } / 60_000,
            songs = songs,
            artists = artists,
            albums = albums,
            songCount = stats.songs.size,
            // Artists of local files have no YouTube id, so they're counted by name.
            artistCount = events.flatMap { event -> event.song.artists.map { it.id ?: it.name } }.distinct().size,
            albumCount = stats.albums.size,
            everySong = everySong,
            cues = cues(songs.map { it.value }, everySong, artists.firstOrNull(), albums.firstOrNull()),
        )
    }

    /** Pictures for top artists you've played but don't follow, kept for next time as Stats keeps them. */
    private suspend fun lookUpPictures(ids: List<String>): Map<String, String> {
        val found = withTimeoutOrNull(10_000) {
            coroutineScope {
                ids.map { id -> async { YouTube.artist(id).getOrNull()?.artist?.thumbnail?.let { id to it } } }.awaitAll()
            }
        }.orEmpty().filterNotNull().toMap()
        Library.rememberArtistPictures(found)
        return found
    }

    /** The phone's track map: which of your songs plays under each page. */
    private fun cues(top: List<Song>, every: List<Song>, artist: ReplayArtist?, album: Library.Ranked<AlbumRef>?): Map<ReplayPage, String> {
        val topSong = top.firstOrNull() ?: return emptyMap()
        // The opening: one from the rest of the top 30, so the top song is kept for its page.
        val intro = top.drop(5).randomOrNull()?.id ?: top.last().id
        val albumSong = album?.let { a -> every.filter { it.album?.id == a.value.id }.randomOrNull()?.id } ?: topSong.id
        // The top artist's own most played, unless that's the top song again, which already had a page.
        val artistSong = artist?.let { a ->
            val theirs = every.filter { song -> song.artists.any { it.id == a.ref.id } }
            when {
                theirs.isEmpty() -> null
                theirs.first().id != topSong.id -> theirs.first().id
                else -> theirs.getOrNull(1)?.id ?: theirs.first().id
            }
        } ?: topSong.id
        return mapOf(
            ReplayPage.Intro to intro,
            ReplayPage.LotOfMusic to intro,
            ReplayPage.Minutes to intro,
            ReplayPage.Songs to topSong.id,
            ReplayPage.TopSong to topSong.id,
            ReplayPage.ArtistBubbles to artistSong,
            ReplayPage.TopArtist to artistSong,
            ReplayPage.ArtistTable to artistSong,
            ReplayPage.AlbumOrbit to albumSong,
            ReplayPage.TopAlbum to albumSong,
            ReplayPage.Summary to ThemeVideo,
        )
    }
}

/**
 * The pages' music, the phone's WrappedAudioService: a player of its own, so your queue is left
 * as it was, starting 30 seconds in where a song has usually got going. Nothing is fetched while
 * it's muted, where the phone plays every page's song silently.
 */
private object ReplayAudio {
    private const val Tag = "ReplayAudio"

    private val engine by lazy {
        AudioEngine(object : AudioEngine.Listener {
            override fun onReady(token: Long, durationMs: Long) = Unit
            override fun onEnded(token: Long) = Unit
            override fun onError(token: Long, error: Throwable, positionMs: Long) = Log.w(Tag, "Couldn't play a Replay song", error)
        })
    }

    private var job: Job? = null
    private var wanted: String? = null
    private var loaded: String? = null
    @Volatile private var muted = true

    fun cue(id: String?) {
        wanted = id
        if (!muted) start()
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) engine.pause() else start()
    }

    fun stop() {
        job?.cancel()
        wanted = null
        loaded = null
        muted = true
        engine.stop()
    }

    private fun start() {
        val id = wanted ?: return
        // The volume can have moved since the last page.
        val volume = PlayerController.state.value.volume
        engine.gain = volume * volume
        if (id == loaded) {
            engine.play()
            return
        }
        loaded = id
        job?.cancel()
        engine.stop()
        job = AppScope.launch(Dispatchers.IO) {
            val (audio, durationMs) = source(id) ?: return@launch
            ensureActive()
            engine.outputDevice = AppSettings.value.outputDevice
            // A song too short to start 30 seconds in starts at the top.
            val start = if (id == Replay.ThemeVideo || (durationMs != null && durationMs < 60_000)) 0L else 30_000L
            engine.load(audio, start, playWhenReady = !muted, durationHintMs = durationMs)
        }
    }

    private suspend fun source(id: String): Pair<CachedAudio, Long?>? = try {
        val local = LocalMusic.track(id).takeIf { LocalMusic.isLocal(id) }
        val offline = Downloads.offline(id)
        when {
            local != null -> File(local.path).takeIf { it.isFile }?.let { CachedAudio.Complete(it) to local.durationMs.takeIf { ms -> ms > 0 } }
            offline != null -> CachedAudio.Complete(offline.second) to offline.first.durationMs
            else -> StreamResolver.resolve(id, AppSettings.value.audioQuality).getOrThrow().let { AudioCache.open(it) to it.durationMs }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(Tag, "Couldn't load $id", e)
        null
    }
}
