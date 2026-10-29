/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ai

import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's AiPlaylistModifier: an instruction for one of your playlists ("remove sad songs,
 * add some 90s hits") turned into songs taken out and songs found and added. The AI sees each
 * song by its number in the list, as the phone gives it the rows' ids.
 */
object AiPlaylistEditor {
    /** Edits the playlist [id]; true when the AI's answer was understood, whatever it changed. */
    suspend fun edit(id: String, instruction: String, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val songs = Library.playlist(id)?.songs ?: return@withContext false
        log("Connecting to AI provider…")
        val current = JSONArray().apply {
            songs.forEachIndexed { i, song -> put(JSONObject().put("id", i + 1).put("title", song.title).put("artist", song.artistText)) }
        }
        val system = """
            You are a highly accurate and strict music historian. The user wants to modify an existing playlist based on a specific prompt.
            The user will provide an INSTRUCTION.
            The CURRENT playlist is:
            $current

            You must output ONLY a valid JSON object containing exactly two arrays: "remove_ids" (list of internal IDs to remove) and "additions" (list of objects with "title" and "artist").

            CRITICAL RULES:
            1. YOU MUST VERIFY THE RELEASE YEAR, MOVIE, AND ARTIST/ACTOR for EVERY SINGLE TRACK you add or remove.
            2. For removals, ONLY include the "id" from the CURRENT playlist that match the user's removal instruction.
            3. For additions, ONLY include songs that match the user's addition instruction.
            4. You MUST output ONLY raw JSON. Do NOT include any markdown formatting (like ```json), explanations, or conversational text.

            Example format:
            {
              "remove_ids": [123, 456],
              "additions": [
                {"title": "Song Name", "artist": "Artist Name"}
              ]
            }
        """.trimIndent()
        val answer = AiChat.complete(system, instruction).getOrElse {
            log(it.message ?: "The AI request failed")
            return@withContext false
        }
        log("Reading the AI's answer…")
        val json = runCatching {
            val text = answer.replace("```json", "").replace("```", "").trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            JSONObject(if (start >= 0 && end > start) text.substring(start, end + 1) else text)
        }.getOrElse {
            log("Couldn't read the AI's answer.")
            return@withContext false
        }
        val removeIds = json.optJSONArray("remove_ids")
        val additions = json.optJSONArray("additions")
        val removing = (0 until (removeIds?.length() ?: 0)).mapNotNull { i -> songs.getOrNull(removeIds!!.optInt(i, 0) - 1) }
        if (removing.isNotEmpty()) {
            log("Removing ${removing.size} songs…")
            removing.forEach { Library.removeFromPlaylist(id, it.id) }
        }
        val adding = additions?.length() ?: 0
        if (adding > 0) {
            log("Searching for $adding new songs…")
            val found = mutableListOf<Song>()
            for (i in 0 until adding) {
                val item = additions!!.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val artist = item.optString("artist")
                log("Searching [${i + 1}/$adding]: $title - $artist")
                YouTube.search("$title $artist", SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.firstOrNull()?.let { it as? SongItem }?.let { found += it.toSong() }
            }
            if (found.isEmpty()) {
                log("Couldn't find any of the suggested songs.")
            } else {
                log("Adding ${found.size} songs…")
                Library.addToPlaylist(id, found)
            }
        }
        log(if (removing.isEmpty() && adding == 0) "The AI asked for no changes." else "Done! Playlist modified.")
        true
    }
}
