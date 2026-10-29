/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ai

import com.convx.desktop.data.Library
import com.convx.desktop.data.RelatedSongs
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.music.innertube.YouTube
import com.music.innertube.SearchFilter
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** An AI DJ mix: its songs in playing order, what couldn't be found, and how it was made. */
class AiDjSet(val name: String, val songs: List<Song>, val unmatched: List<String>, val method: String)

/**
 * The phone's AiDjSetBuilder: a set from a vibe, a song, or both. With a vibe the AI picks from
 * songs related to the seed and from your library (or names its own); a concert set keeps the
 * AI's opener, rising, peak and closer order. With only a song it's that song's related songs,
 * no AI involved.
 *
 * The phone then orders a vibe set by the BPM and key it measured while playing each song. The
 * PC doesn't measure them, so the AI's own order is kept, which is the order the phone falls
 * back to for songs it hasn't measured.
 */
object AiDjSetBuilder {
    private const val MaxLibraryCandidates = 150

    /** Assumed track length, to turn a target duration into a number of songs. */
    private const val AssumedSongSeconds = 210

    private val ConcertRoles = listOf("opener", "rising", "peak", "closer")

    private class Chosen(val title: String, val artist: String, val role: String)

    suspend fun build(
        prompt: String?,
        seed: Song?,
        songCount: Int,
        targetSeconds: Long?,
        concert: Boolean,
        log: (String) -> Unit,
    ): AiDjSet? = withContext(Dispatchers.IO) {
        val count = targetSeconds?.let { (it / AssumedSongSeconds).toInt().coerceAtLeast(1) } ?: songCount
        val vibe = prompt?.trim().orEmpty()
        if (vibe.isEmpty() && seed == null) {
            log("Need a vibe or a song to start from.")
            return@withContext null
        }
        log("Gathering candidates… (vibe: ${if (vibe.isEmpty()) "none" else "\"$vibe\""}, song: ${seed?.title ?: "none"})")
        val related = related(seed, log)

        // A concert with no vibe given is the seed's artist's.
        val concept = vibe.ifBlank { seed?.artists?.firstOrNull()?.name ?: seed?.title.orEmpty() }
        val useAi = vibe.isNotEmpty() || (concert && concept.isNotBlank())
        if (concert && concept.isNotBlank()) {
            search(concept).forEach { if (it.id != seed?.id) related.putIfAbsent(it.id, it) }
        }

        val chosen = mutableListOf<Chosen>()
        if (useAi) {
            log(if (concert) "Asking AI to design a concert set for \"$concept\"…" else "Asking AI to pick songs for the vibe…")
            val pool = pool(related.values, librarySongs())
            val answer = AiChat.complete(prompt(concept, pool, count, concert), concept)
            answer.onFailure { log(it.message ?: "The AI request failed") }
            chosen += parse(answer.getOrNull(), log)
            if (chosen.isNotEmpty()) log("AI picked: " + chosen.joinToString { "${it.title} — ${it.artist} [${it.role}]" })
        }

        val resolved = LinkedHashMap<String, Song>()
        val roles = HashMap<String, String>()
        val unmatched = mutableListOf<String>()
        if (chosen.isNotEmpty()) {
            log("Resolving ${chosen.size} songs…")
            for ((pick, song) in resolve(chosen, related.values, log)) {
                if (song != null && song.id != seed?.id) {
                    resolved.putIfAbsent(song.id, song)
                    roles.putIfAbsent(song.id, pick.role)
                } else if (song == null) {
                    unmatched += pick.title
                }
            }
        } else {
            addRelated(resolved, related.values, targetSeconds, count)
            log("Using ${resolved.size} related songs directly (no vibe given).")
        }
        if (resolved.isEmpty()) {
            log("No songs found.")
            return@withContext null
        }

        val usedAi = chosen.isNotEmpty()
        val method = when {
            concert && usedAi -> "AI-designed concert set for \"$concept\" (opener, rising, peak, closer)"
            usedAi && vibe.isNotEmpty() -> "AI-curated from your vibe"
            seed != null -> "Related songs for \"${seed.title}\", no AI involved"
            else -> "Related songs, no AI involved"
        }
        val ordered = if (concert && roles.isNotEmpty()) {
            // The AI's arc, a part at a time; anything it gave no known part goes at the end.
            val byRole = ConcertRoles.flatMap { role -> resolved.values.filter { roles[it.id] == role } }
            byRole + resolved.values.filter { it !in byRole }
        } else {
            resolved.values.toList()
        }
        val songs = listOfNotNull(seed) + ordered
        val name = name(concert, concept, vibe, seed?.title)
        log("Done! Built \"$name\" with ${songs.size} songs.")
        AiDjSet(name, songs, unmatched, method)
    }

    /** The seed's related songs, or more by its artist when YouTube has none for it. */
    private suspend fun related(seed: Song?, log: (String) -> Unit): LinkedHashMap<String, Song> {
        val related = LinkedHashMap<String, Song>()
        if (seed == null) return related
        val songs = RelatedSongs.get(seed.id) ?: RelatedSongs.fetch(seed.id)
        songs?.forEach { if (it.id != seed.id) related[it.id] = it }
        if (related.isEmpty()) {
            seed.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { artist ->
                log("No related songs for the seed — searching more by $artist instead.")
                search(artist).forEach { if (it.id != seed.id) related[it.id] = it }
            }
        }
        log("Found ${related.size} candidates from the seed.")
        return related
    }

    private suspend fun search(query: String): List<Song> =
        YouTube.search(query, SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>()?.map { it.toSong() }.orEmpty()

    /** What you like and have played, as the phone offers its library: names only, for the AI to lean on. */
    private fun librarySongs(): List<Song> {
        val library = Library.value
        return (library.liked.map { it.song } + library.history.map { it.song }).distinctBy { it.id }.take(MaxLibraryCandidates)
    }

    private fun addRelated(target: MutableMap<String, Song>, related: Collection<Song>, targetSeconds: Long?, count: Int) {
        if (targetSeconds == null) {
            related.take(count).forEach { target[it.id] = it }
            return
        }
        var total = 0L
        for (song in related) {
            if (total >= targetSeconds) break
            target[song.id] = song
            total += song.duration ?: AssumedSongSeconds
        }
    }

    /** Finds each pick, a related song of the same title first, keeping the AI's order. */
    private suspend fun resolve(chosen: List<Chosen>, related: Collection<Song>, log: (String) -> Unit): List<Pair<Chosen, Song?>> =
        coroutineScope {
            val permits = Semaphore(4)
            val done = AtomicInteger()
            chosen.map { pick ->
                async {
                    permits.withPermit {
                        val song = related.firstOrNull { it.title.equals(pick.title, ignoreCase = true) }
                            ?: search(if (pick.artist.isBlank()) pick.title else "${pick.title} ${pick.artist}").firstOrNull()
                        log("Resolving [${done.incrementAndGet()}/${chosen.size}]: ${pick.title}")
                        pick to song
                    }
                }
            }.awaitAll()
        }

    private fun name(concert: Boolean, concept: String, vibe: String, seedTitle: String?): String = when {
        concert -> "Concert: $concept"
        vibe.isNotEmpty() && seedTitle != null -> "AI DJ Mix: $seedTitle + $vibe"
        vibe.isNotEmpty() -> "AI DJ Mix: $vibe"
        seedTitle != null -> "AI DJ Mix: $seedTitle"
        else -> "AI DJ Mix"
    }.take(100)

    private fun pool(related: Collection<Song>, library: List<Song>): String = JSONArray().apply {
        related.forEach { put(JSONObject().put("title", it.title).put("artist", it.artistText)) }
        library.forEach { put(JSONObject().put("title", it.title).put("artist", it.artists.firstOrNull()?.name.orEmpty())) }
    }.toString()

    private fun parse(answer: String?, log: (String) -> Unit): List<Chosen> {
        if (answer == null) return emptyList()
        return try {
            // Models wrap JSON in a code fence or a sentence despite being told not to.
            val json = answer.replace("```json", "").replace("```", "").trim().let { text ->
                val start = text.indexOf('[')
                val end = text.lastIndexOf(']')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
            val songs = JSONArray(json)
            (0 until songs.length()).mapNotNull { i ->
                val song = songs.optJSONObject(i) ?: return@mapNotNull null
                val title = song.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val role = song.optString("role").lowercase().takeIf { it in ConcertRoles } ?: "peak"
                Chosen(title, song.optString("artist"), role)
            }
        } catch (e: Exception) {
            log("Couldn't read the AI's answer, so related songs are used instead.")
            emptyList()
        }
    }

    private fun prompt(concept: String, pool: String, count: Int, concert: Boolean): String = if (concert) {
        """
            You are a knowledgeable concert-setlist designer with real,
            accurate knowledge of "$concept"'s discography and how
            their live shows are actually structured. Research this
            from what you genuinely know — don't guess or pad with
            generic genre filler.

            Rules:
            - Prefer this artist's own real songs (or, if this is a
              genre/vibe rather than one artist, songs that genuinely
              fit it) — not merely similar artists or a loose radio mix.
            - Where it's true to how this artist/genre actually performs
              live, you may include a well-known remix, mashup, or VIP
              edit instead of the plain studio version — use judgement,
              don't force it if their real sets are mostly album cuts.
            - Include a known walk-on/intro moment if this artist
              genuinely opens their real shows with one.
            - The candidates below are a hint, not a limit — use your
              own knowledge of real songs first, and only fall back to
              this list if you don't know better ones:
            $pool

            Design the full running order as an actual concert arc,
            structured into these parts, in this order:
            - opener: 1 song that eases the crowd in, or a known live intro
            - rising: a few songs building energy
            - peak: the biggest hits, highest energy — most of the set
            - closer: 1-2 songs that wind the set down, or a known encore/finale

            List the songs in the exact sequence they should play — the
            order you output IS the running order used directly, with no
            reordering afterward, so put real thought into what follows
            what for a natural energy/tempo flow, not just genre match.

            Pick exactly $count songs total across all four parts.
            Output ONLY a JSON array of objects, in that exact sequence:
            [{"title": "...", "artist": "...", "role": "opener|rising|peak|closer"}].
            Do NOT include markdown formatting, explanations, or any other text.
        """.trimIndent()
    } else {
        """
            You are a DJ curating a set for the user. They describe a vibe or mood.
            Candidate songs, prefer these when they fit (title/artist pairs):
            $pool

            Pick exactly $count songs that best match the requested vibe. You may
            suggest a well-known song not in the candidate list if nothing there fits.
            Output ONLY a JSON array of objects: [{"title": "...", "artist": "..."}].
            Do NOT include markdown formatting, explanations, or any other text.
        """.trimIndent()
    }
}
