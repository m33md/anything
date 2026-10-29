/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reads the playlist files the phone's Backup & restore imports: M3U (BackupRestoreViewModel's
 * loadM3UOnline) and CSV with a column mapping (CsvColumnMappingDialog). Each line becomes a
 * [FileTrack] to find on YouTube Music, or the song itself when the file already names it.
 */
object PlaylistFiles {
    /** One song a playlist file lists: what to search for, or a YouTube id when the file has one. */
    data class FileTrack(
        val title: String,
        val artists: List<String>,
        /** 0 when the file doesn't say. */
        val durationMs: Long = 0,
        val videoId: String? = null,
    )

    data class Parsed(val title: String, val tracks: List<FileTrack>)

    /** The first rows of a CSV and a first guess at which column is which, for the mapping dialog. */
    data class CsvPreview(
        val rows: List<List<String>>,
        val columns: Int,
        val hasHeader: Boolean,
        val artistColumn: Int,
        val titleColumn: Int,
        /** -1 for none. */
        val urlColumn: Int,
        val durationColumn: Int,
    )

    data class CsvMapping(
        val hasHeader: Boolean,
        val artistColumn: Int,
        val titleColumn: Int,
        val urlColumn: Int = -1,
        val durationColumn: Int = -1,
    )

    /** Playlist files are small; a bigger one is not a playlist. */
    const val MaxBytes = 8L * 1024 * 1024

    fun read(file: File): String {
        require(file.length() <= MaxBytes) { "This file is too large for a playlist" }
        val bytes = file.readBytes()
        // UTF-8 is what .m3u8 promises and most tools write. iTunes and Music on Windows export
        // UTF-16, and older .m3u files are often Windows-1252. A byte order mark is dropped.
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            isUtf8(bytes) -> Charsets.UTF_8
            else -> charset("windows-1252")
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun isUtf8(bytes: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
    }.isSuccess

    // ---- M3U --------------------------------------------------------------------------------

    /**
     * The phone reads `#EXTINF:<seconds>,<artist1;artist2> - <title>` lines. This also keeps the
     * seconds for matching, takes a YouTube link on the line after as the song itself, uses
     * `#PLAYLIST:` as the name, and falls back to a file's name when a line has no #EXTINF.
     */
    fun parseM3u(text: String, fallbackTitle: String): Parsed {
        var title = fallbackTitle
        val tracks = mutableListOf<FileTrack>()
        var pending: FileTrack? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> line.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { title = it }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pending?.let { tracks += it }
                    pending = parseExtInf(line)
                }
                line.startsWith("#") -> Unit
                else -> {
                    val id = youTubeId(line)
                    val info = pending
                    pending = null
                    tracks += when {
                        info != null -> info.copy(videoId = id)
                        id != null -> FileTrack("", emptyList(), videoId = id)
                        else -> fromFileName(line) ?: continue
                    }
                }
            }
        }
        pending?.let { tracks += it }
        return Parsed(title, tracks.filter { it.videoId != null || it.title.isNotBlank() })
    }

    private fun parseExtInf(line: String): FileTrack? {
        val body = line.substringAfter(':')
        // Attributes like tvg-name="…" can sit between the seconds and the comma.
        val comma = commaOutsideQuotes(body)
        if (comma < 0) return null
        val seconds = body.substring(0, comma).trim().substringBefore(' ').toDoubleOrNull()
        val metadata = body.substring(comma + 1).trim()
        val (artists, title) = splitArtistTitle(metadata)
        return FileTrack(title, artists, durationMs = seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: 0)
    }

    private fun commaOutsideQuotes(text: String): Int {
        var quoted = false
        text.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted else if (c == ',' && !quoted) return i
        }
        return -1
    }

    /** "Artist1;Artist2 - Title" as the phone writes it; a line with no " - " is just a title. */
    private fun splitArtistTitle(text: String): Pair<List<String>, String> {
        val dash = text.indexOf(" - ")
        if (dash < 0) return emptyList<String>() to text.trim()
        val artists = text.substring(0, dash).split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return artists to text.substring(dash + 3).trim()
    }

    /** A path like `Music/Artist - Title.mp3`, for files that list only paths. */
    private fun fromFileName(path: String): FileTrack? {
        // A web stream's address says nothing about the song.
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return null
        val decoded = if (path.startsWith("file:", ignoreCase = true)) runCatching { URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(path) else path
        val name = decoded.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').trim()
        if (name.isEmpty()) return null
        // "01 - Artist - Title" or "01. Title": a leading track number isn't part of the name.
        val clean = name.replace(Regex("^\\d{1,3}\\s*[-.]\\s*"), "")
        val (artists, title) = splitArtistTitle(clean)
        return FileTrack(title, artists)
    }

    // ---- CSV --------------------------------------------------------------------------------

    /** The first rows and a guess at the columns: by header names when there's a header, else the phone's 0 and 1. */
    fun previewCsv(text: String): CsvPreview {
        val delimiter = delimiterOf(text)
        val rows = parseCsv(text, delimiter, limit = 6)
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val header = rows.firstOrNull().orEmpty().map { it.lowercase().trim() }
        // An exact name first; then a longer name inside a header, so a song called "Say My Name"
        // in a file with no header isn't taken for one.
        fun find(vararg names: String): Int = header.indexOfFirst { h -> names.any { h == it } }
            .takeIf { it >= 0 } ?: header.indexOfFirst { h -> names.any { it.length >= 5 && h.contains(it) } }
        val artist = find("artist name(s)", "artist name", "artist", "artists", "artist(s)", "creator", "interpret")
        val title = find("track name", "song name", "title", "track", "song", "name", "titel")
        val url = find("youtube url", "url", "link", "video id", "videoid")
        val duration = find("duration (ms)", "duration_ms", "duration", "length", "time")
        // The phone always assumes a header. Here it's only assumed when a column is named, so a
        // file without one doesn't lose its first song; the dialog shows the first row either way.
        val hasHeader = artist >= 0 || title >= 0 || url >= 0 || duration >= 0
        return CsvPreview(
            rows = rows,
            columns = columns,
            hasHeader = hasHeader,
            artistColumn = artist.takeIf { it >= 0 } ?: 0,
            titleColumn = title.takeIf { it >= 0 && it != artist } ?: if (columns > 1) 1 else 0,
            urlColumn = url.takeIf { it != artist && it != title } ?: -1,
            durationColumn = duration.takeIf { it != artist && it != title && it != url } ?: -1,
        )
    }

    fun parseCsvTracks(text: String, mapping: CsvMapping): List<FileTrack> {
        val rows = parseCsv(text, delimiterOf(text)).let { if (mapping.hasHeader) it.drop(1) else it }
        return rows.mapNotNull { row ->
            fun cell(i: Int) = if (i in row.indices) row[i].trim() else ""
            val id = if (mapping.urlColumn >= 0) youTubeId(cell(mapping.urlColumn)) else null
            val title = cell(mapping.titleColumn)
            // The phone splits several artists on ";" and ",".
            val artists = cell(mapping.artistColumn).split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            // The phone also needs an artist; a title alone still finds most songs.
            if (id == null && title.isEmpty()) return@mapNotNull null
            FileTrack(title, artists, durationMs = if (mapping.durationColumn >= 0) durationMs(cell(mapping.durationColumn)) else 0, videoId = id)
        }
    }

    /** "215000" from Exportify is milliseconds; "3:35" and "215" are a clock and seconds. */
    private fun durationMs(text: String): Long {
        if (':' in text) {
            val parts = text.split(':').map { it.trim().toLongOrNull() ?: return 0 }
            return parts.fold(0L) { total, part -> total * 60 + part } * 1000
        }
        val number = text.toDoubleOrNull() ?: return 0
        return if (number > 10_000) number.toLong() else (number * 1000).toLong()
    }

    /** A comma unless the first line plainly uses semicolons or tabs, as European Excel does. */
    private fun delimiterOf(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val counts = listOf(',', ';', '\t').associateWith { d -> first.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /** RFC 4180: quoted fields may hold the delimiter, doubled quotes and line breaks. */
    fun parseCsv(text: String, delimiter: Char = ',', limit: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row += field.toString()
            field.clear()
            if (row.size > 1 || row[0].isNotBlank()) rows += row
            row = mutableListOf()
        }
        while (i < text.length && rows.size < limit) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' && (quoted || field.isBlank()) -> { quoted = !quoted; if (quoted) field.clear() }
                quoted -> field.append(c)
                c == delimiter -> { row += field.toString(); field.clear() }
                c == '\r' -> Unit
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (rows.size < limit && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows
    }

    // ---- links --------------------------------------------------------------------------------

    private val IdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val LinkPatterns = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"),
    )

    /** The video id in a YouTube or YouTube Music link, or a bare id. */
    fun youTubeId(text: String): String? {
        val value = text.trim()
        if (value.isEmpty()) return null
        if (IdPattern.matches(value)) return value
        if ("youtu" !in value) return null
        return LinkPatterns.firstNotNullOfOrNull { it.find(value)?.groupValues?.get(1) }
    }
}
