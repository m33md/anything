/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.math.abs

/**
 * Plays Spotify songs from YouTube Music, as the phone's Spotify import does: a Spotify track
 * stands in the queue as a song whose id starts with [Prefix], and is matched to YouTube Music
 * when it is about to play. Matches are kept, so a song is looked up once.
 *
 * The scoring is the phone's SpotifyMapper: title and artist compared by letter pairs after
 * dropping "feat.", brackets, "remaster" and "remix", plus how close the lengths are.
 */
object SpotifyMatcher {
    private const val Tag = "Spotify"
    const val Prefix = "spotify:"

    private const val TitleWeight = 0.45
    private const val ArtistWeight = 0.35
    private const val DurationWeight = 0.20

    /** Below this, the best result is probably another song; better to say so than play it. */
    private const val MinScore = 0.45

    private val matches = JsonFile(
        File(AppDirs.data, "spotify-matches.json"),
        MapSerializer(String.serializer(), Song.serializer()),
    ) { emptyMap() }

    /** Spotify track id to its YouTube Music song; pages watch it so a matched row lights up as it plays. */
    val matched: StateFlow<Map<String, Song>> get() = matches.state

    fun isSpotify(song: Song) = song.id.startsWith(Prefix)

    fun flush() = matches.flush()

    /** The queue's stand-in for a Spotify track; the matched song when it was matched before. */
    fun toSong(track: SpotifyTrack): Song = matches.value[track.id] ?: Song(
        id = Prefix + track.id,
        title = track.title,
        artists = track.artists.map { ArtistRef(null, it) },
        album = null,
        duration = (track.durationMs / 1000).toInt().takeIf { it > 0 },
        thumbnail = track.image,
        explicit = track.explicit,
    )

    /** The YouTube Music song for a Spotify stand-in, or null if nothing close enough exists. */
    suspend fun resolve(song: Song): Song? {
        if (!isSpotify(song)) return song
        val trackId = song.id.removePrefix(Prefix)
        matches.value[trackId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val artist = song.artists.firstOrNull()?.name.orEmpty()
            val query = if (artist.isEmpty()) song.title else "$artist ${song.title}"
            val candidates = YouTube.search(query, SearchFilter.FILTER_SONG)
                .onFailure { Log.w(Tag, "Search for \"$query\" failed", it) }
                .getOrNull()?.items.orEmpty()
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
            val wanted = song.artists.joinToString(" ") { it.name }
            val best = candidates
                .map { it to score(song.title, wanted, (song.duration ?: 0) * 1000L, it.title, it.artists.joinToString(" ") { a -> a.name }, it.duration) }
                .maxByOrNull { it.second }
            if (best == null || best.second < MinScore) {
                Log.w(Tag, "No YouTube Music match for \"$query\" (best ${best?.second})")
                return@withContext null
            }
            // Spotify's cover is usually the better picture of the two; keep it.
            val matched = best.first.toSong().let { if (song.thumbnail != null) it.copy(thumbnail = song.thumbnail) else it }
            matches.update { it + (trackId to matched) }
            matched
        }
    }

    fun score(title: String, artist: String, durationMs: Long, candidateTitle: String, candidateArtist: String, candidateSeconds: Int?): Double {
        val titleScore = similarity(normalize(title), normalize(candidateTitle))
        val artistScore = similarity(normalize(artist), normalize(candidateArtist))
        val durationScore = if (candidateSeconds == null || durationMs <= 0) {
            0.5
        } else {
            when (abs(durationMs / 1000 - candidateSeconds).toInt()) {
                in 0..2 -> 1.0
                in 3..5 -> 0.8
                in 6..10 -> 0.5
                in 11..30 -> 0.2
                else -> 0.0
            }
        }
        return titleScore * TitleWeight + artistScore * ArtistWeight + durationScore * DurationWeight
    }

    private val Feat = Regex("\\(feat\\..*?\\)")
    private val Ft = Regex("\\(ft\\..*?\\)")
    private val Bracket = Regex("\\[.*?]")
    private val Remaster = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val Remix = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val NonAlnum = Regex("[^\\p{L}\\p{N}\\s]")
    private val Spaces = Regex("\\s+")

    private fun normalize(text: String): String = text.lowercase()
        .replace(Feat, "")
        .replace(Ft, "")
        .replace(Bracket, "")
        .replace(Remaster, "")
        .replace(Remix, "")
        .replace(NonAlnum, "")
        .replace(Spaces, " ")
        .trim()

    /** Dice coefficient over letter pairs. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val pairsA = a.windowed(2).toSet()
        val pairsB = b.windowed(2).toSet()
        val shared = pairsA.count { it in pairsB }
        return 2.0 * shared / (pairsA.size + pairsB.size)
    }
}
