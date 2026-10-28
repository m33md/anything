/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class Lyrics(
    val lines: List<LyricLine>,
    val source: String,
) {
    val synced: Boolean get() = lines.any { it.timeMs > 0 }
    val wordSynced: Boolean get() = lines.any { it.isWordSynced }
}

/**
 * The phone's lyric sources, asked in the phone's default order (word-timed sources
 * first). They are all queried at once and the best-ranked answer wins, so a slow
 * source at the top doesn't hold up a fast one below it for long.
 */
object LyricsService {
    private class Source(val name: String, val fetch: suspend (Query) -> List<LyricLine>?)

    private class Query(val videoId: String, val title: String, val artist: String, val album: String?, val durationMs: Long)

    private val sources = listOf(
        Source("lrc.red") { LrcRed.lyrics(it.title, it.artist, it.durationMs) },
        Source("BiniLyrics") { BiniLyrics.lyrics(it.title, it.artist, it.durationMs, it.album)?.lines },
        Source("Better Lyrics") { com.convx.music.lyrics.bitchord.BetterLyrics.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("LyricsPlus") { LyricsPlus.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("Unison") { Unison.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("SimpMusic") { SimpMusicLyrics.lyrics(it.videoId, it.durationMs) },
        Source("KuGou") { KuGou.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("LRCLIB") { LrcLib.lyrics(it.title, it.artist, it.durationMs) },
    )

    private const val PerSourceTimeoutMs = 9_000L
    private val cache = object : LinkedHashMap<String, Lyrics?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>) = size > 120
    }

    fun cached(id: String): Lyrics? = synchronized(cache) { cache[id] }

    suspend fun get(song: Song): Lyrics? {
        synchronized(cache) { if (cache.containsKey(song.id)) return cache[song.id] }
        val result = fetch(song)
        synchronized(cache) { cache[song.id] = result }
        return result
    }

    private suspend fun fetch(song: Song): Lyrics? = coroutineScope {
        val query = Query(
            videoId = song.id,
            title = song.title.forLyricsSearch(),
            artist = (song.artists.firstOrNull()?.name ?: "").artistForLyricsSearch(),
            album = song.album?.name,
            durationMs = (song.duration ?: 0) * 1000L,
        )
        val pending = sources.map { source ->
            source to async {
                try {
                    withTimeoutOrNull(PerSourceTimeoutMs) { source.fetch(query) }?.takeIf { lines -> lines.any { it.text.isNotBlank() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        var best: Lyrics? = null
        for ((source, deferred) in pending) {
            val lines = deferred.await() ?: continue
            best = Lyrics(lines.sortedBy { it.timeMs }, source.name)
            break
        }
        pending.forEach { it.second.cancel() }
        best ?: youTubeLyrics(song)
    }

    /** YouTube Music's own (unsynced) lyrics tab, as a last resort. */
    private suspend fun youTubeLyrics(song: Song): Lyrics? = runCatching {
        val next = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull() ?: return null
        val endpoint = next.lyricsEndpoint ?: return null
        val text = YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        Lyrics(text.lines().map { LyricLine(timeMs = 0, text = it.trim()) }, "YouTube Music")
    }.onFailure { Log.w("Lyrics", "YouTube lyrics failed for ${song.id}", it) }.getOrNull()
}
