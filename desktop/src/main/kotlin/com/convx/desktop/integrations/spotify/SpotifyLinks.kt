/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppScope
import com.convx.desktop.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a song is on Spotify, for links that open the Spotify app (open.spotify.com hands off to
 * it when it's installed). A song that came from Spotify knows its track; signed in to Spotify,
 * others are looked up once; otherwise the link is Spotify's search for the song.
 */
object SpotifyLinks {
    private const val Track = "https://open.spotify.com/track/"
    private val found = ConcurrentHashMap<String, String>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** The best link known right now; [lookUp] may find the exact track a moment later. */
    fun now(song: Song): String = found[song.id] ?: fromImport(song)?.also { found[song.id] = it } ?: search(song)

    /** Whether [now] is the song's own page rather than a search. */
    fun isExact(song: Song): Boolean = found.containsKey(song.id) || fromImport(song) != null

    /** Looks the song up on Spotify once, in the background, when you're signed in there. */
    fun lookUp(song: Song) {
        if (isExact(song) || !asked.add(song.id)) return
        if (SpotifyAccount.state.value !is SpotifyAccount.State.SignedIn) {
            asked.remove(song.id)
            return
        }
        AppScope.launch(Dispatchers.IO) {
            val artist = song.artists.firstOrNull()?.name.orEmpty()
            val results = withTimeoutOrNull(8_000) { SpotifyApi.searchTracks("${song.title} $artist".trim()).getOrNull() }.orEmpty()
            val durationMs = (song.duration ?: 0) * 1000L
            val best = results.maxByOrNull { SpotifyMatcher.score(song.title, artist, durationMs, it.title, it.artists.joinToString(" "), (it.durationMs / 1000).toInt()) }
            val score = best?.let { SpotifyMatcher.score(song.title, artist, durationMs, it.title, it.artists.joinToString(" "), (it.durationMs / 1000).toInt()) } ?: 0.0
            if (best != null && score >= 0.6) found[song.id] = Track + best.id
        }
    }

    /** Spotify's search for the song: the app opens on it, one click from the track. */
    fun search(song: Song): String {
        val query = listOfNotNull(song.title, song.artists.firstOrNull()?.name).joinToString(" ")
        return "https://open.spotify.com/search/" + URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
    }

    /** A stand-in still carrying its Spotify id, or a song matched from a Spotify track. */
    private fun fromImport(song: Song): String? {
        if (SpotifyMatcher.isSpotify(song)) return Track + song.id.removePrefix(SpotifyMatcher.Prefix)
        val trackId = SpotifyMatcher.matched.value.entries.firstOrNull { it.value.id == song.id }?.key ?: return null
        return Track + trackId
    }
}
