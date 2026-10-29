/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.lyrics.LyricsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A song kept on this PC, with what playback needs to start it without asking YouTube. */
@Serializable
data class DownloadedSong(
    val song: Song,
    /** The file's name in [Downloads.dir]. */
    val file: String,
    val mimeType: String,
    val bitrate: Int = 0,
    val sampleRate: Int? = null,
    val loudnessDb: Double? = null,
    val durationMs: Long? = null,
    val bytes: Long = 0,
    val at: Long = System.currentTimeMillis(),
)

/**
 * The phone's downloads: songs kept on this PC so they play with no connection. Two download at
 * a time (the phone runs three over a phone's connection, which is usually slower to reach). A
 * download goes through the same ranged fetch as playback, so a song that's playing isn't fetched
 * twice. With "Save downloads to Music folder" on, as on the phone, each download is also copied
 * to Music\Convx as Artist - Title, with its lyrics beside it.
 */
object Downloads {
    private const val Tag = "Downloads"
    private const val Parallel = 2

    sealed interface Status {
        data object Queued : Status
        data class Running(val done: Long, val total: Long) : Status {
            val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
        data class Failed(val message: String) : Status
    }

    /** A download not finished yet: the song, and how far it's got. */
    data class Pending(val song: Song, val status: Status)

    /** All of a list's songs at once, as the phone's album and playlist buttons show them. */
    sealed interface Collective {
        data object None : Collective
        data class Running(val done: Int, val total: Int) : Collective
        data object Done : Collective
    }

    val dir: File by lazy { File(AppDirs.data, "downloads").apply { mkdirs() } }

    /** Where the phone's "Save downloads to Music folder" puts its copies on a PC. */
    val exportDir: File get() = File(System.getProperty("user.home"), "Music${File.separator}Convx")

    private val store = JsonFile(
        File(AppDirs.data, "downloads.json"),
        MapSerializer(String.serializer(), DownloadedSong.serializer()),
    ) { emptyMap() }

    /** Finished downloads by song id, newest last. */
    val done: StateFlow<Map<String, DownloadedSong>> = store.state

    private val _active = MutableStateFlow<Map<String, Pending>>(emptyMap())
    /** Downloads waiting, running or failed, by song id, in the order they were asked for. */
    val active: StateFlow<Map<String, Pending>> = _active.asStateFlow()

    private val queue = Channel<Song>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun flush() = store.flush()

    fun start() {
        AppScope.launch(Dispatchers.IO) {
            // Files deleted by hand stop counting as downloaded; leftovers of a quit mid-download go.
            val missing = store.value.filterValues { !File(dir, it.file).isFile }.keys
            if (missing.isNotEmpty()) store.update { it - missing }
            val kept = store.value.values.mapTo(HashSet()) { it.file }
            dir.listFiles()?.forEach { if (it.name !in kept) it.delete() }
        }
        repeat(Parallel) {
            AppScope.launch(Dispatchers.IO) {
                for (song in queue) {
                    if (_active.value[song.id]?.status != Status.Queued) continue
                    coroutineScope {
                        val job = launch { fetch(song) }
                        jobs[song.id] = job
                        job.join()
                        jobs.remove(song.id, job)
                    }
                }
            }
        }
        // The phone's "Auto download on like".
        AppScope.launch {
            Library.changes.collect { change ->
                if (change is Library.Change.Like && change.liked && AppSettings.value.autoDownloadOnLike) download(listOf(change.song))
            }
        }
    }

    /** Files on this PC and Spotify songs not yet matched have nothing to download. */
    fun canDownload(song: Song) = !LocalMusic.isLocal(song.id) && !SpotifyMatcher.isSpotify(song)

    fun isDownloaded(id: String) = done.value[id]?.let { File(dir, it.file).isFile } == true

    /** Queues what isn't downloaded or downloading already; returns how many were queued. */
    fun download(songs: List<Song>): Int {
        val fresh = songs.filter { canDownload(it) && !isDownloaded(it.id) && _active.value[it.id]?.status.let { s -> s == null || s is Status.Failed } }
            .distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        _active.update { current -> current + fresh.associate { it.id to Pending(it, Status.Queued) } }
        fresh.forEach { queue.trySend(it) }
        return fresh.size
    }

    /** Stops downloads that haven't finished; a song that's playing keeps streaming. */
    fun cancel(ids: Collection<String>) {
        _active.update { it - ids.toSet() }
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            if (PlayerController.state.value.current?.id != id) AudioCache.cancel(id)
        }
    }

    fun remove(ids: Collection<String>) {
        cancel(ids.filter { it in _active.value })
        val gone = ids.mapNotNull { store.value[it] }
        store.update { it - ids.toSet() }
        gone.forEach { File(dir, it.file).delete() }
    }

    fun removeAll() = remove(store.value.keys + _active.value.keys)

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Where a list's songs stand: all downloaded, some on the way, or neither. */
    fun collective(songs: List<Song>, done: Map<String, DownloadedSong>, active: Map<String, Pending>): Collective {
        val wanted = songs.filter(::canDownload)
        if (wanted.isEmpty()) return Collective.None
        val have = wanted.count { it.id in done }
        if (have == wanted.size) return Collective.Done
        val pending = wanted.count { active[it.id]?.status.let { s -> s is Status.Queued || s is Status.Running } }
        return if (pending > 0) Collective.Running(have, wanted.size) else Collective.None
    }

    /** The stream and file for a downloaded song, so playback needn't ask YouTube. */
    fun offline(id: String): Pair<ResolvedStream, File>? {
        val entry = done.value[id] ?: return null
        val file = File(dir, entry.file).takeIf { it.isFile } ?: return null
        val stream = ResolvedStream(
            videoId = id,
            url = file.toURI().toString(),
            mimeType = entry.mimeType,
            bitrate = entry.bitrate,
            sampleRate = entry.sampleRate,
            contentLength = file.length(),
            loudnessDb = entry.loudnessDb,
            durationMs = entry.durationMs,
            client = "Downloaded",
            expiresAt = Long.MAX_VALUE,
        )
        return stream to file
    }

    /** Only while the download is still wanted; a cancelled one stays gone. */
    private fun setStatus(id: String, status: Status) =
        _active.update { current -> current[id]?.let { current + (id to it.copy(status = status)) } ?: current }

    /** For the local file scan: the Music folder copies are downloads, not separate songs. */
    fun isExportFolder(folder: File): Boolean =
        folder.absolutePath.trimEnd('\\', '/').equals(exportDir.absolutePath, ignoreCase = true)

    private suspend fun fetch(song: Song) {
        val id = song.id
        var part: File? = null
        try {
            setStatus(id, Status.Running(0, 0))
            val export = AppSettings.value.saveDownloadsToMusic
            val stream = StreamResolver.resolve(id, AppSettings.value.audioQuality, preferAac = export).getOrThrow()
            val source = AudioCache.fetchComplete(stream) { got, total ->
                setStatus(id, Status.Running(got, total))
            }
            val target = File(dir, "$id.${stream.container}")
            part = File(dir, target.name + ".part").also { source.copyTo(it, overwrite = true) }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            part = null
            record(song, target, stream)
            _active.update { it - id }
            Log.i(Tag, "Downloaded ${song.title} (${stream.badge})")
            if (export) {
                runCatching { exportToMusic(song, target) }.onFailure { Log.w(Tag, "Couldn't copy ${song.title} to the Music folder", it) }
            }
        } catch (error: Throwable) {
            part?.delete()
            if (error is CancellationException) {
                _active.update { it - id }
                throw error
            }
            Log.w(Tag, "Download of $id failed", error)
            setStatus(id, Status.Failed(error.message ?: "Download failed"))
        }
    }

    /** Notes a file in [dir] as [song]'s download. */
    internal fun record(song: Song, file: File, stream: ResolvedStream) = store.update {
        it + (song.id to DownloadedSong(song, file.name, stream.mimeType, stream.bitrate, stream.sampleRate, stream.loudnessDb, stream.durationMs, file.length()))
    }

    /** The phone's DownloadExporter: the audio as Artist - Title, its lyrics as .lrc, its cover in a hidden folder. */
    private suspend fun exportToMusic(song: Song, file: File) {
        val folder = exportDir.apply { mkdirs() }
        val base = exportBaseName(song.artistText, song.title)
        file.copyTo(File(folder, "$base.${file.extension}"), overwrite = true)
        lyricsText(song)?.let { File(folder, "$base.lrc").writeText(it) }
        song.thumbnail?.let { url ->
            runCatching {
                val art = File(folder, ".artwork").apply { mkdirs() }
                runCatching { Files.setAttribute(art.toPath(), "dos:hidden", true) }
                val request = Request.Builder().url(url.replace(CoverSize, "=w1200-h1200-l90-rj")).build()
                Http.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    File(art, "$base.jpg").outputStream().use { response.body.byteStream().copyTo(it) }
                }
            }.onFailure { Log.w(Tag, "Couldn't save the cover of ${song.title}", it) }
        }
    }

    /** The song's lyrics as an .lrc file, waiting a little for the lookup. */
    private suspend fun lyricsText(song: Song): String? {
        val state = withTimeoutOrNull(20_000) {
            LyricsService.state(song).first { it is LyricsState.Missing || (it is LyricsState.Ready && it.final) }
        } ?: LyricsService.state(song).value
        val lyrics = (state as? LyricsState.Ready)?.lyrics ?: return null
        return lyrics.lines.filter { it.text.isNotBlank() }.joinToString("\n") { line ->
            if (lyrics.synced) lrcTime(line.timeMs) + line.text else line.text
        }.ifBlank { null }
    }

    /** An .lrc time tag, like [01:23.45]. */
    internal fun lrcTime(ms: Long): String =
        String.format(Locale.ROOT, "[%02d:%05.2f]", ms / 60_000, (ms % 60_000) / 1000.0)

    private val InvalidName = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val CoverSize = Regex("=w\\d+-h\\d+[^/]*$")

    /** The phone's exportBaseName: "Artist - Title", safe for a file name. */
    fun exportBaseName(artist: String, title: String): String =
        listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Untitled" }
            .replace(InvalidName, "_").trim().take(120)
}
