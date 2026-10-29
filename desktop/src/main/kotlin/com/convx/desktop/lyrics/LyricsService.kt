/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Song
import com.convx.music.lyrics.LyricsProviderRegistry
import com.convx.music.lyrics.bitchord.BetterLyrics
import com.convx.music.lyrics.bitchord.Genius
import com.convx.music.lyrics.bitchord.BiniLyrics
import com.convx.music.lyrics.bitchord.KuGou
import com.convx.music.lyrics.bitchord.LrcLib
import com.convx.music.lyrics.bitchord.LrcRed
import com.convx.music.lyrics.bitchord.LyricLine
import com.convx.music.lyrics.bitchord.LyricsPlus
import com.convx.music.lyrics.bitchord.Megalobiz
import com.convx.music.lyrics.bitchord.Musixmatch
import com.convx.music.lyrics.bitchord.PaxSenix
import com.convx.music.lyrics.bitchord.SimpMusicLyrics
import com.convx.music.lyrics.bitchord.Unison
import com.convx.music.lyrics.bitchord.artistForLyricsSearch
import com.convx.music.lyrics.bitchord.forLyricsSearch
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
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

    private class Source(val id: String, val name: String, val fetch: suspend (Query) -> List<LyricLine>?)

    private class Query(val song: Song, val title: String, val artist: String, val album: String?, val durationMs: Long)

    /** Every source the phone has, under the phone's ids, so its saved order means the same here. */
    private val all = listOf(
        Source("LrcRed", "lrc.red") { LrcRed.lyrics(it.title, it.artist, it.durationMs) },
        Source("BiniLyrics", "BiniLyrics") { BiniLyrics.lyrics(it.title, it.artist, it.durationMs, it.album)?.lines },
        Source("BetterLyrics", "Better Lyrics") { BetterLyrics.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("BetterLyricsPortato", "BetterLyrics Portato") { BetterLyrics.portato(it.title, it.artist, it.durationMs, it.album) },
        Source("Paxsenix", "PaxSenix") { PaxSenix.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("PaxsenixSpotify", "PaxSenix: Spotify") { if (paxSenixKey) PaxSenix.spotifyLyrics(it.title, it.artist, it.durationMs) else null },
        Source("PaxsenixMusixmatch", "PaxSenix: Musixmatch") { if (paxSenixKey) PaxSenix.musixmatchLyrics(it.title, it.artist, it.durationMs) else null },
        Source("YouLyPlus", "LyricsPlus") { LyricsPlus.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("Unison", "Unison") { Unison.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("SimpMusic", "SimpMusic") { SimpMusicLyrics.lyrics(it.song.id, it.durationMs) },
        Source("YouTubeSubtitle", "YouTube Subtitle") { youTubeSubtitles(it.song) },
        Source("YouTubeMusic", "YouTube Music") { youTubeLyrics(it.song) },
        Source("Megalobiz", "Megalobiz") { Megalobiz.lyrics(it.title, it.artist) },
        Source("Kugou", "KuGou") { KuGou.lyrics(it.title, it.artist, it.durationMs, it.album) },
        Source("LrcLib", "LRCLIB") { LrcLib.lyrics(it.title, it.artist, it.durationMs) },
        Source("Musixmatch", "Musixmatch") { Musixmatch.lyrics(it.title, it.artist, it.durationMs) },
        Source("Genius", "Genius") { Genius.lyrics(it.title, it.artist) },
    ).associateBy { it.id }

    /** A source as Settings lists it. */
    class Provider(val id: String, val name: String)

    /** Every source in the order Settings has them, switched off ones included, as the phone lists them. */
    fun providers(order: List<String> = AppSettings.value.lyricsProviders): List<Provider> =
        LyricsProviderRegistry.completeProviderOrder(order.joinToString(","))
            .mapNotNull { all[it] }
            .map { Provider(it.id, it.name) }

    /** The sources to ask, best first: the order from Settings without the ones switched off. */
    private fun sources(): List<Source> {
        val settings = AppSettings.value
        return providers(settings.lyricsProviders).filter { it.id !in settings.lyricsProvidersOff }.mapNotNull { all[it.id] }
    }

    /** The phone keeps PaxSenix's key with the other lyric credentials; here it's in [Secrets]. */
    const val PaxSenixKey = "paxsenix.key"

    private val paxSenixKey: Boolean get() = !Secrets[PaxSenixKey].isNullOrBlank()

    fun setPaxSenixKey(key: String?) {
        Secrets[PaxSenixKey] = key?.trim()
        PaxSenix.setApiKey(key?.trim().orEmpty())
        forget()
    }

    init {
        Secrets[PaxSenixKey]?.let(PaxSenix::setApiKey)
    }

    /** Drops what was found, so the next look uses the sources as they are now. */
    fun forget() = synchronized(entries) { entries.clear() }

    private const val PerSourceTimeoutMs = 10_000L

    /** A song that found nothing is asked again after this long, in case the network was down. */
    private const val MissingRetryMs = 90_000L

    /** One source's answer for one song, as the lyrics menu's source list shows it. */
    sealed interface SourceResult {
        data object Asking : SourceResult
        data class Found(val lyrics: Lyrics) : SourceResult
        data object NotFound : SourceResult
        data object Failed : SourceResult
    }

    private class Entry(val state: MutableStateFlow<LyricsState>, var missingAt: Long = 0L) {
        /** Every source's answer so far, by id; a source cut short once a better one won is left out. */
        val results = MutableStateFlow<Map<String, SourceResult>>(emptyMap())
        var job: Job? = null
    }

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
        start(song, entry)
        entry
    }

    /** Shows the lyrics kept for [song] from the lyrics menu, or starts asking the sources. */
    private fun start(song: Song, entry: Entry) {
        entry.job?.cancel()
        val kept = LyricsTweaks.of(song.id)
        if (kept.lyrics != null) {
            entry.state.value = LyricsState.Ready(Lyrics(parseLyricsText(kept.lyrics), kept.source ?: "You"), final = true)
            return
        }
        entry.state.value = LyricsState.Loading
        entry.job = AppScope.launch(Dispatchers.IO) {
            val result = runCatching { fetch(song, entry) }
                .onFailure { if (it is CancellationException) throw it else Log.w(Tag, "Lookup failed for ${song.id}", it) }
                .getOrNull()
            if (result == null) entry.missingAt = System.currentTimeMillis()
            entry.state.value = result?.let { LyricsState.Ready(it, final = true) } ?: LyricsState.Missing
        }
    }

    /** What each source answered for [song], for the lyrics menu. */
    fun results(song: Song): StateFlow<Map<String, SourceResult>> = entry(song).results

    /** The phone's Refetch: forgets lyrics kept for [song] and asks every source again. */
    fun refetch(song: Song) {
        LyricsTweaks.update(song.id) { it.copy(lyrics = null, source = null) }
        val entry = entry(song)
        entry.results.value = emptyMap()
        start(song, entry)
    }

    /** Shows [lyrics] for [song] from now on, as the phone keeps an edit or a chosen result. */
    fun use(song: Song, lyrics: Lyrics) {
        LyricsTweaks.update(song.id) { it.copy(lyrics = lyrics.lines.toLyricsText(), source = lyrics.source) }
        val entry = entry(song)
        entry.job?.cancel()
        entry.state.value = LyricsState.Ready(lyrics, final = true)
    }

    /** Asks one source for [song], as tapping a source the lookup didn't reach does on the phone. */
    suspend fun ask(song: Song, id: String): Lyrics? {
        val source = all[id] ?: return null
        val entry = entry(song)
        entry.results.update { it + (id to SourceResult.Asking) }
        val answer = answer(source, query(song))
        entry.results.update { it + (id to answer) }
        return (answer as? SourceResult.Found)?.lyrics
    }

    /**
     * The phone's Search lyrics: every source switched on asked for [title] by [artist] as typed,
     * each answer as it lands.
     */
    fun search(song: Song, title: String, artist: String): Flow<Lyrics> = channelFlow {
        val query = Query(song, title.trim(), artist.trim(), song.album?.name, (song.duration ?: 0) * 1000L)
        for (source in sources()) {
            launch { (answer(source, query) as? SourceResult.Found)?.let { send(it.lyrics) } }
        }
    }.flowOn(Dispatchers.IO)

    private fun query(song: Song) = Query(
        song = song,
        title = song.title.forLyricsSearch(),
        artist = (song.artists.firstOrNull()?.name ?: "").artistForLyricsSearch(),
        album = song.album?.name,
        durationMs = (song.duration ?: 0) * 1000L,
    )

    private suspend fun answer(source: Source, query: Query): SourceResult {
        val lines = try {
            withTimeoutOrNull(PerSourceTimeoutMs) { source.fetch(query) ?: emptyList() } ?: return SourceResult.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SourceResult.Failed
        }
        return lines.takeIf { l -> l.any { it.text.isNotBlank() } }
            ?.let { SourceResult.Found(Lyrics(it.sortedBy { line -> line.timeMs }, source.name)) }
            ?: SourceResult.NotFound
    }

    private suspend fun fetch(song: Song, entry: Entry): Lyrics? = coroutineScope {
        val query = query(song)
        val sources = sources()
        if (sources.isEmpty()) return@coroutineScope null
        val out = entry.state
        entry.results.value = sources.associate { it.id to SourceResult.Asking }
        val started = System.nanoTime()
        val answers = Channel<Pair<Int, SourceResult>>(Channel.UNLIMITED)
        val jobs = sources.mapIndexed { index, source ->
            launch { answers.send(index to answer(source, query)) }
        }

        var best: Pair<Int, Lyrics>? = null
        val answered = BooleanArray(sources.size)
        try {
            repeat(sources.size) {
                val (index, result) = answers.receive()
                answered[index] = true
                entry.results.update { it + (sources[index].id to result) }
                val lyrics = (result as? SourceResult.Found)?.lyrics
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
            // Sources cut short never answered; the menu offers to ask them.
            entry.results.update { results -> results.filterValues { it != SourceResult.Asking } }
        }
    }

    /** The video's captions, line timed, as the phone's YouTube Subtitle source reads them. */
    private suspend fun youTubeSubtitles(song: Song): List<LyricLine>? {
        if (song.id.startsWith("file-")) return null
        val lrc = YouTube.transcript(song.id).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() }
    }

    /** YouTube Music's own (unsynced) lyrics tab, the last resort. */
    private suspend fun youTubeLyrics(song: Song): List<LyricLine>? {
        val next = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull() ?: return null
        val endpoint = next.lyricsEndpoint ?: return null
        val text = YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return text.lines().map { LyricLine(timeMs = 0, text = it.trim()) }
    }
}
