/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.music.lyrics.bitchord.LyricAlignment
import com.convx.music.lyrics.bitchord.LyricLine
import com.convx.music.lyrics.bitchord.TimedRun
import com.convx.music.lyrics.bitchord.withInstrumentalGaps
import com.convx.music.lyrics.bitchord.wordsFromRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Spicy Lyrics' official API (developers.spicylyrics.org): community syllable syncs, then Apple
 * Music's, then Spotify's, for a Spotify track id, with a key the user makes there.
 *
 * Its terms shape this: the answer's source, and a community sync's uploader and maker, are shown
 * wherever the lyrics are ([LyricsCredit]); nothing it returns is written to an .lrc file; and a
 * kept copy is dropped after 30 days ([LyricsService]).
 *
 * Songs are found by their Spotify id: the one an imported Spotify song came with, else a search
 * through the linked Spotify account, else song.link's lookup of the YouTube video.
 */
object SpicyLyrics {
    private const val Tag = "SpicyLyrics"
    private const val Base = "https://api.spicylyrics.org/v1/lyrics/"
    private const val SongLink = "https://api.song.link/v1-alpha.1/links"
    const val KeyName = "spicylyrics.key"

    /** A kept copy of its lyrics older than this is fetched again, as the terms ask. */
    const val KeepForMs = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val credits = ConcurrentHashMap<String, LyricsCredit>()
    private val spotifyIds = ConcurrentHashMap<String, String>()
    private val YouTubeId = Regex("^[A-Za-z0-9_-]{11}$")

    private val _problem = MutableStateFlow<String?>(null)

    /** What went wrong last, for Settings, or null while it answers. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    val hasKey: Boolean get() = !Secrets[KeyName].isNullOrBlank()

    fun setKey(key: String?) {
        Secrets[KeyName] = key?.trim()?.takeIf { it.isNotEmpty() }
        _problem.value = null
    }

    /** Who to credit for the lyrics last found for [song]. */
    fun credit(song: Song): LyricsCredit? = credits[song.id]

    suspend fun lyrics(song: Song, title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val key = Secrets[KeyName]?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val trackId = spotifyId(song, title, artist, durationMs) ?: run {
            Log.i(Tag, "No Spotify id for \"$title\" by $artist")
            return@withContext null
        }
        val request = Request.Builder()
            .url(Base + trackId)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .header("User-Agent", Http.USER_AGENT)
            .build()
        val (code, body) = Http.client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        when (code) {
            200 -> Unit
            404 -> return@withContext null
            401, 403 -> {
                _problem.value = "Spicy Lyrics refused the key ($code). Check that it's copied whole and that client access allows no origin header."
                Log.w(Tag, "Key refused ($code): ${body.take(200)}")
                return@withContext null
            }
            429 -> {
                _problem.value = "Spicy Lyrics says this key has asked too often; it will answer again later."
                Log.w(Tag, "Rate limited: ${body.take(200)}")
                return@withContext null
            }
            else -> {
                Log.w(Tag, "$trackId answered $code: ${body.take(200)}")
                return@withContext null
            }
        }
        _problem.value = null
        val root = json.parseToJsonElement(body).jsonObject
        val data = root.obj("Body") ?: root
        val lines = parse(data, durationMs)
        if (lines.isNullOrEmpty()) {
            // The response's documented shape is the syllable one; say what else came, to fix it from a log.
            Log.w(Tag, "Couldn't read a ${data.str("Type")} answer; it has ${data.keys} and items like ${(data["Content"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.keys }}")
            return@withContext null
        }
        credits[song.id] = creditOf(data)
        Log.i(Tag, "${data.str("Type")} lyrics from ${data.str("source")} for $trackId")
        lines
    }

    // ---- Reading the answer ----------------------------------------------------------------

    private fun parse(data: JsonObject, durationMs: Long): List<LyricLine>? {
        val content = data["Content"] as? JsonArray
        val static = data.str("Type").equals("Static", ignoreCase = true)
        if (content == null || static && content.none { (it as? JsonObject)?.obj("Lead") != null }) {
            val rows = (data["Lines"] as? JsonArray) ?: content ?: return null
            return rows.mapNotNull { (it as? JsonObject)?.str("Text")?.trim() }
                .map { LyricLine(0L, it) }
                .takeIf { it.any { line -> line.text.isNotBlank() } }
        }
        val toMs = timeScale(content, durationMs)
        val lines = content.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val alignment = if (item.bool("OppositeAligned") == true) LyricAlignment.End else LyricAlignment.Start
            val lead = item.obj("Lead")
            when {
                lead != null -> vocal(lead, toMs)?.let { main ->
                    val backing = (item["Background"] as? JsonArray)?.mapNotNull { vocal(it as? JsonObject, toMs) }.orEmpty()
                    main.copy(alignment = alignment, background = merge(backing))
                }
                item["Syllables"] is JsonArray -> vocal(item, toMs)?.copy(alignment = alignment)
                else -> {
                    val text = item.str("Text")?.trim() ?: return@mapNotNull null
                    val start = item.num("StartTime") ?: return@mapNotNull null
                    LyricLine(toMs(start), text, sungUntilMs = item.num("EndTime")?.let(toMs), alignment = alignment)
                }
            }
        }
        return lines.sortedBy { it.timeMs }.withInstrumentalGaps().takeIf { it.any { line -> line.text.isNotBlank() } }
    }

    /** One voice's line from its syllables; [TimedRun]s glue "e" and "nough" back into a word. */
    private fun vocal(obj: JsonObject?, toMs: (Double) -> Long): LyricLine? {
        obj ?: return null
        val syllables = obj["Syllables"] as? JsonArray ?: return null
        val runs = syllables.mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            val text = s.str("Text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = s.num("StartTime") ?: return@mapNotNull null
            val end = s.num("EndTime") ?: start
            TimedRun(toMs(start), toMs(end), if (s.bool("IsPartOfWord") == true) text else "$text ")
        }
        if (runs.isEmpty()) return null
        val words = wordsFromRuns(runs, spacingIsExplicit = true)
        if (words.isEmpty()) return null
        return LyricLine(
            timeMs = obj.num("StartTime")?.let(toMs)?.let { minOf(it, words.first().startMs) } ?: words.first().startMs,
            text = words.joinToString(" ") { it.text },
            words = words,
            sungUntilMs = obj.num("EndTime")?.let(toMs) ?: words.last().endMs,
        )
    }

    /** Several backing voices under one line become one backing line, as the views draw one. */
    private fun merge(lines: List<LyricLine>): LyricLine? = when (lines.size) {
        0 -> null
        1 -> lines.first()
        else -> LyricLine(
            timeMs = lines.minOf { it.timeMs },
            text = lines.joinToString(" ") { it.text },
            words = lines.flatMap { it.words },
            sungUntilMs = lines.mapNotNull { it.sungUntilMs }.maxOrNull(),
        )
    }

    /**
     * Seconds or milliseconds: the documented example is in seconds (7.357), but nothing says so,
     * so the song's length decides when there is one.
     */
    private fun timeScale(content: JsonArray, durationMs: Long): (Double) -> Long {
        val latest = content.maxOfOrNull { element ->
            val item = element as? JsonObject
            maxOf(item?.num("EndTime") ?: 0.0, item?.obj("Lead")?.num("EndTime") ?: 0.0, item?.num("StartTime") ?: 0.0)
        } ?: 0.0
        val seconds = if (durationMs > 0) latest * 1000 <= durationMs + 120_000 else latest < 3_600
        val scale = if (seconds) 1000.0 else 1.0
        return { time -> (time * scale).toLong() }
    }

    private fun creditOf(data: JsonObject): LyricsCredit {
        val provider = when (val source = data.str("source")) {
            "spicy_lyrics" -> "Spicy Lyrics"
            "apple_music" -> "Apple Music"
            "spotify" -> "Spotify"
            null, "" -> "an unknown source"
            else -> source.replace('_', ' ')
        }
        val upload = data.obj("UploadAttribution")
        fun person(o: JsonObject?): CreditLink? = o?.str("username")?.takeIf { it.isNotBlank() }?.let { CreditLink(it, o?.str("url")) }
        return LyricsCredit(provider, person(upload?.obj("Uploader")), person(upload?.obj("Maker")))
    }

    // ---- Finding the Spotify id ----------------------------------------------------------------

    private suspend fun spotifyId(song: Song, title: String, artist: String, durationMs: Long): String? {
        spotifyIds[song.id]?.let { return it }
        val id = when {
            SpotifyMatcher.isSpotify(song) -> song.id.removePrefix(SpotifyMatcher.Prefix)
            else -> SpotifyMatcher.spotifyIdOf(song.id)
                ?: fromSpotifySearch(title, artist, durationMs)
                ?: fromSongLink(song.id)
        }
        if (id != null) spotifyIds[song.id] = id
        return id
    }

    /** The linked Spotify account's own search, scored the way imports are matched. */
    private suspend fun fromSpotifySearch(title: String, artist: String, durationMs: Long): String? {
        if (SpotifyAccount.state.value !is SpotifyAccount.State.SignedIn || title.isBlank()) return null
        val query = if (artist.isBlank()) title else "track:$title artist:$artist"
        val tracks = SpotifyApi.searchTracks(query, 5).getOrNull().orEmpty()
        val best = tracks.maxByOrNull {
            SpotifyMatcher.score(title, artist, durationMs, it.title, it.artists.joinToString(" "), (it.durationMs / 1000).toInt())
        } ?: return null
        val score = SpotifyMatcher.score(title, artist, durationMs, best.title, best.artists.joinToString(" "), (best.durationMs / 1000).toInt())
        return best.id.takeIf { score >= 0.6 }
    }

    /** song.link's free lookup of a YouTube video's Spotify track, for when Spotify isn't linked or answering. */
    private fun fromSongLink(videoId: String): String? {
        if (!YouTubeId.matches(videoId)) return null
        val target = URLEncoder.encode("https://music.youtube.com/watch?v=$videoId", Charsets.UTF_8)
        val request = Request.Builder()
            .url("$SongLink?url=$target&songIfSingle=true")
            .header("User-Agent", Http.USER_AGENT)
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(Tag, "song.link answered ${response.code} for $videoId")
                    return null
                }
                val spotify = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject.obj("linksByPlatform")?.obj("spotify")
                spotify?.str("entityUniqueId")?.substringAfter("::", "")?.takeIf { it.isNotEmpty() }
                    ?: spotify?.str("url")?.substringAfter("/track/", "")?.substringBefore('?')?.takeIf { it.isNotEmpty() }
            }
        }.onFailure { Log.w(Tag, "song.link lookup failed for $videoId: ${it.message}") }.getOrNull()
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.num(key: String): Double? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
