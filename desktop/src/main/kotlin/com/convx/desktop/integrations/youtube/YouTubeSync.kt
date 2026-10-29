/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.youtube

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Library
import com.convx.desktop.data.LikedSong
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.utils.completed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's "Auto sync with account": your YouTube Music likes, albums, artists and playlists
 * come into Convx, and what you like, save or follow in Convx goes to YouTube Music.
 *
 * The phone mirrors the account exactly. Convx on a PC already has a library of its own when
 * you sign in, so it is gentler: things only leave your Convx library when they leave your
 * account after Convx saw them there, and what you had before signing in stays put.
 */
object YouTubeSync {
    private const val Tag = "YouTubeSync"
    private const val PeriodMs = 30 * 60_000L

    sealed interface Status {
        data object Idle : Status
        data class Running(val step: String) : Status
        data class Done(val at: Long, val liked: Int, val albums: Int, val artists: Int, val playlists: Int) : Status
        data class Failed(val message: String) : Status
    }

    /** What sync last saw on the account, so it can tell "removed there" from "never there". */
    @Serializable
    data class Known(
        val liked: Set<String> = emptySet(),
        val albums: Set<String> = emptySet(),
        val artists: Set<String> = emptySet(),
        val playlists: Set<String> = emptySet(),
    )

    private val store = JsonFile(File(AppDirs.data, "youtube-sync.json"), Known.serializer()) { Known() }
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()
    private var job: Job? = null

    /** When you last liked, saved or followed each id yourself; sync leaves those alone. */
    private val touched = ConcurrentHashMap<String, Long>()

    private val enabled: Boolean get() = YouTubeAccount.signedIn && AppSettings.value.youtubeSync

    fun start() {
        AppScope.launch { Library.changes.collect { push(it) } }
        AppScope.launch {
            delay(5_000)
            while (true) {
                syncNow()
                delay(PeriodMs)
            }
        }
    }

    fun syncNow() {
        if (!enabled || job?.isActive == true) return
        job = AppScope.launch(Dispatchers.IO) { mutex.withLock { run() } }
    }

    fun flush() = store.flush()

    /** Forgets what the last account had, e.g. after switching channel; nothing is removed. */
    fun forgetKnown() = store.update { Known() }

    /** The phone's "Clear Data" on log out: removes what came from, or went to, the account. */
    fun clearSynced() {
        val known = store.value
        Library.applySync { data ->
            data.copy(
                liked = data.liked.filterNot { it.song.id in known.liked },
                albums = data.albums.filterNot { it.browseId in known.albums },
                artists = data.artists.filterNot { it.id in known.artists },
                playlists = data.playlists.filterNot { it.id in known.playlists },
            )
        }
        forgetKnown()
        _status.value = Status.Idle
    }

    private suspend fun run() {
        val started = System.currentTimeMillis()
        try {
            _status.value = Status.Running("Liked songs")
            val liked = YouTube.playlist("LM").completed().getOrThrow().songs.map { it.toSong() }
            _status.value = Status.Running("Albums")
            val albums = YouTube.library("FEmusic_liked_albums").completed().getOrThrow().items.filterIsInstance<AlbumItem>().map { it.toSaved() }
            _status.value = Status.Running("Artists")
            val artists = YouTube.library("FEmusic_library_corpus_artists").completed().getOrThrow().items.filterIsInstance<ArtistItem>().map { it.toSaved() }
            _status.value = Status.Running("Playlists")
            val playlists = YouTube.library("FEmusic_liked_playlists").completed().getOrThrow().items.filterIsInstance<PlaylistItem>()
                .filterNot { it.id == "LM" || it.id == "SE" }
                .map { it.toSaved() }
            if (!enabled) return

            val known = store.value
            val now = System.currentTimeMillis()
            fun mine(id: String) = (touched[id] ?: 0L) >= started
            Library.applySync { data ->
                data.copy(
                    liked = merge(data.liked, liked, known.liked, { it.song.id }, { it.id }, ::mine) { song, i -> LikedSong(song, now - i * 1000L) }
                        .sortedByDescending { it.likedAt },
                    albums = merge(data.albums, albums, known.albums, { it.browseId }, { it.browseId }, ::mine) { a, i -> a.copy(savedAt = now - i * 1000L) },
                    artists = merge(data.artists, artists, known.artists, { it.id }, { it.id }, ::mine) { a, i -> a.copy(savedAt = now - i * 1000L) },
                    playlists = merge(data.playlists, playlists, known.playlists, { it.id }, { it.id }, ::mine) { p, i -> p.copy(savedAt = now - i * 1000L) },
                )
            }
            store.update {
                Known(
                    liked = liked.mapTo(HashSet()) { it.id },
                    albums = albums.mapTo(HashSet()) { it.browseId },
                    artists = artists.mapTo(HashSet()) { it.id },
                    playlists = playlists.mapTo(HashSet()) { it.id },
                )
            }
            _status.value = Status.Done(System.currentTimeMillis(), liked.size, albums.size, artists.size, playlists.size)
            Log.i(Tag, "Synced ${liked.size} liked songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(Tag, "Sync failed", error)
            _status.value = Status.Failed(error.message ?: "Couldn't reach YouTube Music")
        }
    }

    /**
     * Adds what's new on the account (in its order, newest first) and drops what the account
     * had last time but no longer does. Ids you changed yourself during the sync are left alone.
     */
    private inline fun <L, R> merge(
        local: List<L>,
        remote: List<R>,
        known: Set<String>,
        localId: (L) -> String,
        remoteId: (R) -> String,
        mine: (String) -> Boolean,
        create: (R, Int) -> L,
    ): List<L> {
        val remoteIds = remote.mapTo(HashSet(), remoteId)
        val kept = local.filterNot { val id = localId(it); id in known && id !in remoteIds && !mine(id) }
        val have = kept.mapTo(HashSet(), localId)
        val added = remote.withIndex()
            .filter { (_, item) -> remoteId(item).let { it !in have && !mine(it) } }
            .map { (index, item) -> create(item, index) }
        return added + kept
    }

    /** Sends what you just did in Convx to YouTube Music, as the phone's entities do. */
    private suspend fun push(change: Library.Change) {
        if (!enabled) return
        withContext(Dispatchers.IO) {
            runCatching {
                when (change) {
                    is Library.Change.Like -> {
                        touched[change.song.id] = System.currentTimeMillis()
                        YouTube.likeVideo(change.song.id, change.liked).getOrThrow()
                        store.update { it.copy(liked = if (change.liked) it.liked + change.song.id else it.liked - change.song.id) }
                    }
                    is Library.Change.Album -> {
                        val playlistId = change.album.playlistId ?: return@runCatching
                        touched[change.album.browseId] = System.currentTimeMillis()
                        YouTube.likePlaylist(playlistId, change.saved).getOrThrow()
                        store.update { it.copy(albums = if (change.saved) it.albums + change.album.browseId else it.albums - change.album.browseId) }
                    }
                    is Library.Change.Playlist -> {
                        val id = change.playlist.id
                        if (id.startsWith("local-") || id == "LM" || id == "SE") return@runCatching
                        touched[id] = System.currentTimeMillis()
                        YouTube.likePlaylist(id.removePrefix("VL"), change.saved).getOrThrow()
                        store.update { it.copy(playlists = if (change.saved) it.playlists + id else it.playlists - id) }
                    }
                    is Library.Change.Artist -> {
                        val id = change.artist.id
                        touched[id] = System.currentTimeMillis()
                        val channelId = YouTube.getChannelId(id).ifEmpty { id.takeIf { it.startsWith("UC") } } ?: return@runCatching
                        YouTube.subscribeChannel(channelId, change.followed).getOrThrow()
                        store.update { it.copy(artists = if (change.followed) it.artists + id else it.artists - id) }
                    }
                }
            }.onFailure { Log.w(Tag, "Couldn't send $change to YouTube Music", it) }
        }
    }
}
