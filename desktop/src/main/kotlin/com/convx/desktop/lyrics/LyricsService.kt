/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.Song
import com.convx.music.lyrics.bitchord.BiniLyrics
import com.convx.music.lyrics.bitchord.KuGou
import com.convx.music.lyrics.bitchord.LrcLib
import com.convx.music.lyrics.bitchord.LrcRed
import com.convx.music.lyrics.bitchord.LyricLine
import com.convx.music.lyrics.bitchord.LyricsPlus
import com.convx.music.lyrics.bitchord.SimpMusicLyrics
import com.convx.music.lyrics.bitchord.Unison
import com.convx.music.lyrics.bitchord.artistForLyricsSearch
import com.convx.music.lyrics.bitchord.forLyricsSearch
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class Lyrics(
    val lines: List<LyricLine>,
    val source: String,
) {
    val synced: Boolean get() = lines.any { it.timeMs > 0 }
    val wordSynced: Boolean get() = lines.any { it.isWordSynced }

    /** 2 for word timing, 1 for line timing, 0 for plain text. */
    val syncLevel: Int get() = when {
        wordSynced -> 2
        synced -> 1
        else -> 0
    }
}

sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState

    /** [final] is false while a better source (word timed, or ranked higher) may still answer. */
    data class Ready(val lyrics: Lyrics, val final: Boolean) : LyricsState
}

/**
 * The phone's lyric sources in the phone's default order, word-timed sources first.
 *
 * Like the phone's LyricsHelper, the first usable answer shows at once and a better one
 * replaces it when it lands: word timing beats line timing beats plain text, and between
 * equals the higher-ranked source wins. Unlike the phone every source is asked at the same
 * time, since a desktop isn't short of sockets, and the lookup starts as the song starts
 * (and for the song after it), so the lyrics are usually there before anyone opens them.
 */
object LyricsService {
    private const val Tag = "Lyrics"

    private class Source(val name: String, val fetch: suspend (Query) -> List<LyricLine>?)

    private class Query(val song: Song, val title: String, val artist: String, val album: String?, val durationMs: Long)

    private val sources = listOf(
        Source("lrc.red") { LrcRed.lyrics(it.title, it.artist, it.durationMs) },
        Source("BiniLyrics") { BiniLyrics.lyrics(it.title, it.artist, it.durationMs, it.album)?.lines },
        Source("Better Lyrics") { com.convx.music.lyrics.bitchord.BetterLyrics.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("LyricsPlus") { LyricsPlus.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("Unison") { Unison.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("SimpMusic") { SimpMusicLyrics.lyrics(it.song.id, it.durationMs) },
        Source("KuGou") { KuGou.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("LRCLIB") { LrcLib.lyrics(it.title, it.artist, it.durationMs) },
        Source("YouTube Music") { youTubeLyrics(it.song) },
    )

    private const val PerSourceTimeoutMs = 10_000L

    /** A song that found nothing is asked again after this long, in case the network was down. */
    private const val MissingRetryMs = 90_000L

    private class Entry(val state: MutableStateFlow<LyricsState>, var missingAt: Long = 0L)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 120
    }

    /** Starts looking up [song]'s lyrics unless that is already done or under way. */
    fun prefetch(song: Song) {
        entry(song)
    }

    /** [song]'s lyrics as they arrive; starts the lookup if needed. */
    fun state(song: Song): StateFlow<LyricsState> = entry(song).state

    private fun entry(song: Song): Entry = synchronized(entries) {
        val existing = entries[song.id]
        val stale = existing != null && existing.state.value == LyricsState.Missing &&
            System.currentTimeMillis() - existing.missingAt > MissingRetryMs
        if (existing != null && !stale) return existing
        val entry = existing ?: Entry(MutableStateFlow(LyricsState.Loading)).also { entries[song.id] = it }
        entry.state.value = LyricsState.Loading
        AppScope.launch(Dispatchers.IO) {
            val result = runCatching { fetch(song, entry.state) }
                .onFailure { if (it is CancellationException) throw it else Log.w(Tag, "Lookup failed for ${song.id}", it) }
                .getOrNull()
            if (result == null) entry.missingAt = System.currentTimeMillis()
            entry.state.value = result?.let { LyricsState.Ready(it, final = true) } ?: LyricsState.Missing
        }
        entry
    }

    private suspend fun fetch(song: Song, out: MutableStateFlow<LyricsState>): Lyrics? = coroutineScope {
        val query = Query(
            song = song,
            title = song.title.forLyricsSearch(),
            artist = (song.artists.firstOrNull()?.name ?: "").artistForLyricsSearch(),
            album = song.album?.name,
            durationMs = (song.duration ?: 0) * 1000L,
        )
        val started = System.nanoTime()
        val answers = Channel<Pair<Int, Lyrics?>>(Channel.UNLIMITED)
        val jobs = sources.mapIndexed { index, source ->
            launch {
                val lines = try {
                    withTimeoutOrNull(PerSourceTimeoutMs) { source.fetch(query) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val lyrics = lines?.takeIf { l -> l.any { it.text.isNotBlank() } }
                    ?.let { Lyrics(it.sortedBy { line -> line.timeMs }, source.name) }
                answers.send(index to lyrics)
            }
        }

        var best: Pair<Int, Lyrics>? = null
        val answered = BooleanArray(sources.size)
        try {
            repeat(sources.size) {
                val (index, lyrics) = answers.receive()
                answered[index] = true
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(Tag, "${sources[index].name}: ${lyrics?.let { listOf("plain", "lines", "words")[it.syncLevel] } ?: "nothing"} after $ms ms")
                val current = best
                if (lyrics != null && (current == null || lyrics.syncLevel > current.second.syncLevel ||
                        (lyrics.syncLevel == current.second.syncLevel && index < current.first))
                ) {
                    best = index to lyrics
                    out.value = LyricsState.Ready(lyrics, final = false)
                }
                // Done once the best is word timed and nothing ranked above it is still out.
                val b = best
                if (b != null && b.second.wordSynced && (0 until b.first).all { i -> answered[i] }) return@coroutineScope b.second
            }
            best?.second
        } finally {
            jobs.forEach { it.cancel() }
            answers.cancel()
        }
    }

    /** YouTube Music's own (unsynced) lyrics tab, the last resort. */
    private suspend fun youTubeLyrics(song: Song): List<LyricLine>? {
        val next = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull() ?: return null
        val endpoint = next.lyricsEndpoint ?: return null
        val text = YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return text.lines().map { LyricLine(timeMs = 0, text = it.trim()) }
    }
}
