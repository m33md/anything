/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.AppDirs
import com.convx.desktop.data.JsonFile
import com.convx.desktop.player.PlayerController
import com.convx.music.lyrics.bitchord.LrcLib
import com.convx.music.lyrics.bitchord.LyricLine
import com.convx.music.lyrics.bitchord.toEnhancedLrc
import com.convx.music.lyrics.bitchord.toLrc
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What the lyrics menu changed about one song, as the phone keeps it on the song and its lyrics
 * row: the offset, "Romanize current track", lyrics you edited or picked, and the AI translation.
 */
@Serializable
data class SongLyricsTweaks(
    /** Added to the playhead before a line is picked, so a positive offset shows lines sooner. */
    val offsetMs: Int = 0,
    val romanize: Boolean = true,
    /** Lyrics you typed or chose, as LRC (enhanced when word timed) or plain lines; null uses the sources. */
    val lyrics: String? = null,
    /** Where [lyrics] came from, as the menu names it. */
    val source: String? = null,
    val translation: SavedTranslation? = null,
)

/** One song's translation, line for line with its non-empty lines, and what it was made for. */
@Serializable
data class SavedTranslation(
    val lines: List<String>,
    val language: String,
    val mode: String,
    /** [LyricsTranslator.key] of the lyrics it translates, so other lyrics don't wear it. */
    val key: Int,
)

@Serializable
data class LyricsTweaksData(val songs: Map<String, SongLyricsTweaks> = emptyMap())

object LyricsTweaks {
    private val store = JsonFile(File(AppDirs.data, "lyrics.json"), LyricsTweaksData.serializer()) { LyricsTweaksData() }
    val state: StateFlow<LyricsTweaksData> get() = store.state

    fun of(songId: String?): SongLyricsTweaks = songId?.let { store.value.songs[it] } ?: SongLyricsTweaks()

    fun update(songId: String, transform: (SongLyricsTweaks) -> SongLyricsTweaks) = store.update { data ->
        val next = transform(data.songs[songId] ?: SongLyricsTweaks())
        // A song back at the defaults has nothing worth keeping.
        data.copy(songs = if (next == SongLyricsTweaks()) data.songs - songId else data.songs + (songId to next))
    }

    fun flush() = store.flush()

    /** Seeks to a lyric line of the song playing, undoing its offset as the phone does. */
    fun seek(lineTimeMs: Long) {
        val offset = of(PlayerController.state.value.current?.id).offsetMs
        PlayerController.seekTo((lineTimeMs - offset).coerceAtLeast(0L))
    }
}

private val Stamp = Regex("""\[\d{1,3}:\d{2}[.:]\d{2,3}]""")

/** Lines as text you can edit and keep: enhanced LRC when word timed, LRC when line timed, else plain. */
fun List<LyricLine>.toLyricsText(): String = when {
    any { it.isWordSynced } -> toEnhancedLrc()
    any { it.timeMs > 0 } -> toLrc()
    else -> joinToString("\n") { it.text }
}

/** The other way: LRC (enhanced or not) back to timed lines, anything else to plain lines. */
fun parseLyricsText(text: String): List<LyricLine> =
    if (Stamp.containsMatchIn(text)) {
        LrcLib.parseLrc(text)
    } else {
        text.trim().lines().map { LyricLine(timeMs = 0, text = it.trim()) }
    }
