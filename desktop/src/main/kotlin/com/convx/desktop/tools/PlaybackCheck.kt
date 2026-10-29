/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.tools

import com.convx.desktop.audio.AudioEffects
import com.convx.desktop.audio.EffectsChain
import com.convx.desktop.audio.EqBand
import com.convx.desktop.audio.EqFilter
import com.convx.desktop.audio.Equalizer
import com.convx.desktop.audio.ParametricEq
import com.convx.desktop.audio.softClip
import com.convx.desktop.core.Log
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.data.Song
import com.convx.desktop.lyrics.toLyricsText
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.CachedAudio
import com.convx.desktop.player.GrowingFileInputStream
import com.convx.desktop.player.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import kotlin.system.exitProcess

/**
 * Plays songs without a sound card: resolves each stream, downloads it through the cache
 * and decodes the start plus a seek further in, exactly as the player does. Prints one
 * line per song and exits non-zero if none worked. Usage: `PlaybackCheck [videoId...]`.
 */
fun main(args: Array<String>) {
    val ids = args.toList().ifEmpty {
        listOf(
            // Two uploads of Blinding Lights and Despacito: popular, long-lived ids.
            "4NRXx6U8ABQ",
            "fHI8X4OXluQ",
            "kJQP7kiw5Fk",
        )
    }
    val dspOk = dspCheck() and libraryCheck() and partyCheck() and backupCheck() and playlistFileCheck() and downloadsCheck() and lyricsMenuCheck() and homeOrderCheck() and statsCheck()
    partyServerCheck()
    runBlocking(Dispatchers.IO) { initYouTube() }
    var passed = 0
    for (id in ids) {
        val result = runCatching { tryPlay(id) }
        result.onSuccess {
            passed++
            println("PASS $id $it")
        }.onFailure {
            Log.e("PlaybackCheck", "Failed $id", it)
            println("FAIL $id ${it::class.simpleName}: ${it.message}")
        }
    }
    println("$passed of ${ids.size} songs played")
    exitProcess(if (passed > 0 && dspOk) 0 else 1)
}

/**
 * Things that need no network: a FLAC file Convx writes itself and then reads back as a local
 * song, an Apple Music XML export, and proxy addresses.
 */
private fun libraryCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} library $name: $detail")
    }

    val track = runCatching {
        val dir = java.nio.file.Files.createTempDirectory("convx-local").toFile()
        val file = java.io.File(dir, "tone.flac")
        val rate = 44_100
        org.bytedeco.javacv.FFmpegFrameRecorder(file, 2).apply {
            format = "flac"
            sampleRate = rate
            audioCodec = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC
            setMetadata("title", "Convx Test Tone")
            setMetadata("artist", "Convx; Second Artist")
            setMetadata("album", "Checks")
            setMetadata("track", "3/12")
            start()
            val samples = ShortArray(rate * 2 * 2) { i -> (8000 * kotlin.math.sin(2 * Math.PI * 440 * (i / 2) / rate)).toInt().toShort() }
            recordSamples(rate, 2, java.nio.ShortBuffer.wrap(samples))
            stop()
            release()
        }
        com.convx.desktop.player.LocalMusic.read(file).also { dir.deleteRecursively() }
    }.onFailure { it.printStackTrace() }.getOrNull()
    expect("local file title", track?.title == "Convx Test Tone", "${track?.title}")
    expect("local file artists", track?.artists == listOf("Convx", "Second Artist"), "${track?.artists}")
    expect("local file album and track", track?.album == "Checks" && track?.track == 3, "${track?.album} #${track?.track}")
    expect("local file length", (track?.durationMs ?: 0) in 1900..2100, "${track?.durationMs} ms")
    expect("local file codec", track?.codec == "flac", "${track?.codec}")

    val export = runCatching {
        com.convx.music.canvas.ApplePlaylistExport.parse(
            (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                    "<plist version=\"1.0\"><dict><key>Tracks</key><dict>" +
                    "<key>101</key><dict><key>Track ID</key><integer>101</integer><key>Name</key><string>Blinding Lights</string>" +
                    "<key>Artist</key><string>The Weeknd</string><key>Total Time</key><integer>200040</integer></dict>" +
                    "<key>102</key><dict><key>Track ID</key><integer>102</integer><key>Name</key><string>A Video</string>" +
                    "<key>Artist</key><string>X</string><key>Has Video</key><true/></dict></dict>" +
                    "<key>Playlists</key><array><dict><key>Name</key><string>Road Trip</string><key>Playlist Items</key><array>" +
                    "<dict><key>Track ID</key><integer>101</integer></dict><dict><key>Track ID</key><integer>102</integer></dict>" +
                    "</array></dict></array></dict></plist>"
                ).toByteArray(),
        )
    }.onFailure { it.printStackTrace() }.getOrNull()
    expect(
        "Apple Music export",
        export?.title == "Road Trip" && export?.tracks?.size == 1 && export?.unsupportedTracks == 1 && export?.tracks?.firstOrNull()?.durationMs == 200040,
        "${export?.title}, ${export?.tracks?.size} tracks, ${export?.unsupportedTracks} skipped",
    )
    val http = com.convx.desktop.core.Network.parse("HTTP", "127.0.0.1:8080")
    val socks = com.convx.desktop.core.Network.parse("SOCKS", "proxy.example.com:1080")
    expect(
        "proxy addresses",
        http?.type() == java.net.Proxy.Type.HTTP && socks?.type() == java.net.Proxy.Type.SOCKS &&
            com.convx.desktop.core.Network.parse("HTTP", "nohost") == null && com.convx.desktop.core.Network.parse("HTTP", "h:99999") == null,
        "$http, $socks",
    )
    return ok
}

/** The party's clock maths and invite links, which need no network. */
private fun partyCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} party $name: $detail")
    }
    val playing = com.convx.desktop.integrations.party.PartyPlayback(isPlaying = true, positionMs = 10_000, anchorMs = 1_000_000)
    val paused = playing.copy(isPlaying = false)
    val sync = com.convx.desktop.integrations.party.PartySync
    expect("position while playing", sync.positionAt(playing, 1_002_500) == 12_500L, "${sync.positionAt(playing, 1_002_500)}")
    expect("position while paused", sync.positionAt(paused, 1_002_500) == 10_000L, "${sync.positionAt(paused, 1_002_500)}")
    expect("resume anchored ahead", sync.startDelayMs(playing, 999_700) == 300L && sync.positionAt(playing, 999_700) == 10_000L, "${sync.startDelayMs(playing, 999_700)} ms")
    val clock = com.convx.desktop.integrations.party.PartyClock()
    clock.observe(clientSentMs = 1_000, serverMs = 6_100, receivedMs = 1_400) // 400 ms round trip
    clock.observe(clientSentMs = 2_000, serverMs = 7_050, receivedMs = 2_100) // 100 ms: the one to trust
    expect("clock offset", clock.offsetMs() == 5_000L, "${clock.offsetMs()} ms")
    val party = com.convx.desktop.integrations.party.Party
    val link = party.parseInvite("convx://party/ab12cd?server=https%3A%2F%2Fexample.com")
    expect("invite link", link?.code == "AB12CD" && link?.server == "https://example.com", "$link")
    expect("typed code", party.parseInvite(" ab-12 cd ")?.code == "AB12CD" && party.parseInvite("hi") == null, "${party.parseInvite(" ab-12 cd ")}")
    return ok
}

/**
 * Builds a small phone backup the way the phone's Room database lays it out (song.db, version 40)
 * and checks what Backup reads from it: files only the phone has are left out and counted.
 */
private fun backupCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} backup $name: $detail")
    }
    val dir = java.nio.file.Files.createTempDirectory("convx-backup-check").toFile()
    try {
        Class.forName("org.sqlite.JDBC")
        val db = java.io.File(dir, "song.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
            connection.createStatement().use { st ->
                listOf(
                    "CREATE TABLE song (id TEXT PRIMARY KEY, title TEXT NOT NULL, duration INTEGER NOT NULL, thumbnailUrl TEXT, albumId TEXT, albumName TEXT, explicit INTEGER NOT NULL, liked INTEGER NOT NULL, likedDate INTEGER, isLocal INTEGER NOT NULL, isVideo INTEGER NOT NULL)",
                    "CREATE TABLE artist (id TEXT PRIMARY KEY, name TEXT NOT NULL, thumbnailUrl TEXT, bookmarkedAt INTEGER, isLocal INTEGER NOT NULL)",
                    "CREATE TABLE album (id TEXT PRIMARY KEY, playlistId TEXT, title TEXT NOT NULL, year INTEGER, thumbnailUrl TEXT, bookmarkedAt INTEGER, inLibrary INTEGER, isLocal INTEGER NOT NULL)",
                    "CREATE TABLE playlist (id TEXT PRIMARY KEY, name TEXT NOT NULL, browseId TEXT, createdAt INTEGER, lastUpdateTime INTEGER, bookmarkedAt INTEGER, thumbnailUrl TEXT, isEditable INTEGER NOT NULL)",
                    "CREATE TABLE playlist_song_map (id INTEGER PRIMARY KEY, playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL)",
                    "CREATE TABLE song_artist_map (songId TEXT NOT NULL, artistId TEXT NOT NULL, position INTEGER NOT NULL)",
                    "CREATE TABLE album_artist_map (albumId TEXT NOT NULL, artistId TEXT NOT NULL, `order` INTEGER NOT NULL)",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY, songId TEXT NOT NULL, timestamp INTEGER NOT NULL, playTime INTEGER NOT NULL)",
                    "INSERT INTO song VALUES ('fJ9rUzIMcZQ', 'Bohemian Rhapsody', 355, 'https://i.ytimg.com/a.jpg', 'MPREb_queen', 'A Night at the Opera', 0, 1, 1700000000000, 0, 0)",
                    "INSERT INTO song VALUES ('5NV6Rdv1a3I', 'Get Lucky', 369, NULL, NULL, NULL, 0, 1, 1710000000000, 0, 0)",
                    "INSERT INTO song VALUES ('local-123', 'Demo tape', 120, NULL, NULL, NULL, 0, 1, 1720000000000, 1, 0)",
                    "INSERT INTO song VALUES ('dQw4w9WgXcQ', 'Never Gonna Give You Up', 213, NULL, NULL, NULL, 0, 0, NULL, 0, 1)",
                    "INSERT INTO artist VALUES ('UCiMhD4jzUqG-IgPzUmmytRQ', 'Queen', NULL, 1690000000000, 0)",
                    "INSERT INTO artist VALUES ('UCdaftpunk', 'Daft Punk', NULL, NULL, 0)",
                    "INSERT INTO artist VALUES ('pharrell-local', 'Pharrell Williams', NULL, NULL, 1)",
                    "INSERT INTO song_artist_map VALUES ('fJ9rUzIMcZQ', 'UCiMhD4jzUqG-IgPzUmmytRQ', 0)",
                    "INSERT INTO song_artist_map VALUES ('5NV6Rdv1a3I', 'pharrell-local', 1)",
                    "INSERT INTO song_artist_map VALUES ('5NV6Rdv1a3I', 'UCdaftpunk', 0)",
                    "INSERT INTO album VALUES ('MPREb_queen', 'OLAK5uy_queen', 'A Night at the Opera', 1975, NULL, NULL, 1695000000000, 0)",
                    "INSERT INTO album VALUES ('MPREb_unsaved', NULL, 'Not saved', NULL, NULL, NULL, NULL, 0)",
                    "INSERT INTO album_artist_map VALUES ('MPREb_queen', 'UCiMhD4jzUqG-IgPzUmmytRQ', 0)",
                    "INSERT INTO playlist VALUES ('p1', 'Road trip', NULL, 1650000000000, 1660000000000, NULL, NULL, 1)",
                    "INSERT INTO playlist VALUES ('p2', 'Chill mix', 'VLPLchill', 1655000000000, NULL, 1656000000000, NULL, 0)",
                    "INSERT INTO playlist_song_map VALUES (1, 'p1', '5NV6Rdv1a3I', 1)",
                    "INSERT INTO playlist_song_map VALUES (2, 'p1', 'fJ9rUzIMcZQ', 0)",
                    "INSERT INTO playlist_song_map VALUES (3, 'p1', 'local-123', 2)",
                    "INSERT INTO event VALUES (1, 'fJ9rUzIMcZQ', 1700000100000, 200000)",
                    "INSERT INTO event VALUES (2, '5NV6Rdv1a3I', 1700000500000, 300000)",
                    "INSERT INTO event VALUES (3, 'local-123', 1700000900000, 100000)",
                ).forEach { st.executeUpdate(it) }
            }
        }
        val backup = java.io.File(dir, "Convx_20261029120000.backup")
        java.util.zip.ZipOutputStream(backup.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("settings.preferences_pb"))
            zip.putNextEntry(java.util.zip.ZipEntry("song.db"))
            db.inputStream().use { it.copyTo(zip) }
        }
        val phone = com.convx.desktop.integrations.Backup.readPhone(backup)
        expect("liked songs", phone.liked.map { it.song.id } == listOf("5NV6Rdv1a3I", "fJ9rUzIMcZQ"), "${phone.liked.map { it.song.title }}")
        val lucky = phone.liked.first().song
        expect("artists in order", lucky.artists.map { it.name } == listOf("Daft Punk", "Pharrell Williams") && lucky.artists[1].id == null, "${lucky.artists}")
        val queen = phone.liked.last().song
        expect("album and length", queen.album?.id == "MPREb_queen" && queen.duration == 355 && queen.thumbnail != null, "${queen.album} ${queen.duration}s")
        val trip = phone.playlists.singleOrNull()
        expect("playlist", trip?.id == "local-phone-p1" && trip.songs.map { it.title } == listOf("Bohemian Rhapsody", "Get Lucky"), "${trip?.name}: ${trip?.songs?.map { it.title }}")
        expect("saved playlist", phone.savedPlaylists.map { it.id } == listOf("PLchill"), "${phone.savedPlaylists}")
        expect("saved album", phone.albums.map { it.browseId } == listOf("MPREb_queen") && phone.albums[0].artists.map { it.name } == listOf("Queen") && phone.albums[0].year == 1975, "${phone.albums}")
        expect("followed artist", phone.artists.map { it.name } == listOf("Queen"), "${phone.artists.map { it.name }}")
        expect("history", phone.history.map { it.song.id } == listOf("5NV6Rdv1a3I", "fJ9rUzIMcZQ") && phone.history[0].at == 1700000500000, "${phone.history.map { it.song.title to it.at }}")
        expect("phone-only files", phone.skippedLocal == 2, "${phone.skippedLocal} left out")
    } catch (error: Throwable) {
        expect("read", false, "${error::class.simpleName}: ${error.message}")
    } finally {
        dir.deleteRecursively()
    }
    return ok
}

/**
 * Downloads without YouTube: a file noted as a song's download plays from disk with no lookup,
 * lists' buttons count it, and the Music folder copies get the phone's names.
 */
private fun downloadsCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} downloads $name: $detail")
    }
    val downloads = com.convx.desktop.player.Downloads
    val song = Song("cHk-dl-chk0", "Check song", listOf(ArtistRef(name = "Convx")), duration = 3)
    val other = Song("cHk-dl-chk1", "Other song", listOf(ArtistRef(name = "Convx")))
    val local = Song("${com.convx.desktop.player.LocalMusic.Prefix}check", "A file")
    try {
        expect("names", downloads.exportBaseName("AC/DC", "Back In Black?") == "AC_DC - Back In Black_" && downloads.exportBaseName(" ", "") == "Untitled",
            downloads.exportBaseName("AC/DC", "Back In Black?"))
        expect("lrc time", downloads.lrcTime(83_450) == "[01:23.45]" && downloads.lrcTime(605_000) == "[10:05.00]", downloads.lrcTime(83_450))
        expect("files aren't downloads", !downloads.canDownload(local) && downloads.canDownload(song), "")

        val file = java.io.File(downloads.dir, "${song.id}.m4a").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val stream = com.convx.desktop.player.ResolvedStream(
            song.id, "https://example.invalid", "audio/mp4; codecs=\"mp4a.40.2\"", 128_000, 44_100, file.length(), -7.5, 3_000, "check", 0,
        )
        downloads.record(song, file, stream)
        val offline = downloads.offline(song.id)
        expect(
            "plays from disk",
            offline != null && offline.second == file && offline.first.contentLength == 4096L && offline.first.loudnessDb == -7.5 &&
                offline.first.expiresAt == Long.MAX_VALUE && downloads.isDownloaded(song.id),
            "${offline?.first?.client} ${offline?.second?.name}",
        )
        expect("not downloaded", downloads.offline(other.id) == null && !downloads.isDownloaded(other.id), "")

        val done = downloads.done.value
        val running = mapOf(other.id to com.convx.desktop.player.Downloads.Pending(other, com.convx.desktop.player.Downloads.Status.Running(10, 100)))
        val failed = mapOf(other.id to com.convx.desktop.player.Downloads.Pending(other, com.convx.desktop.player.Downloads.Status.Failed("x")))
        val c1 = downloads.collective(listOf(song, local), done, emptyMap())
        val c2 = downloads.collective(listOf(song, other), done, running)
        val c3 = downloads.collective(listOf(song, other), done, failed)
        val c4 = downloads.collective(listOf(local), done, emptyMap())
        expect(
            "album buttons",
            c1 == com.convx.desktop.player.Downloads.Collective.Done &&
                c2 == com.convx.desktop.player.Downloads.Collective.Running(1, 2) &&
                c3 == com.convx.desktop.player.Downloads.Collective.None &&
                c4 == com.convx.desktop.player.Downloads.Collective.None,
            "$c1 $c2 $c3 $c4",
        )

        downloads.remove(listOf(song.id))
        expect("remove", !file.exists() && downloads.offline(song.id) == null, "file ${if (file.exists()) "still there" else "gone"}")
    } catch (error: Throwable) {
        expect("run", false, "${error::class.simpleName}: ${error.message}")
    }
    return ok
}

/** Home's section order, by the phone's rules: default, arranged, hidden, and shuffled with the card on top. */
private fun homeOrderCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} home order $name: $detail")
    }
    val song = com.convx.desktop.data.Song("abc", "Song")
    val playlist = com.music.innertube.models.PlaylistItem("PL1", "Mix", null, null, null, null, null, null)
    val state = com.convx.desktop.ui.screens.HomeFeedState(
        quickPicks = listOf(song),
        forgottenFavorites = listOf(song),
        topSongs = listOf(song),
        communityPlaylists = listOf(playlist),
        seed = 42,
    )
    val base = com.convx.desktop.data.Settings(homeRandomOrder = false)
    fun ids(settings: com.convx.desktop.data.Settings) =
        com.convx.desktop.ui.screens.buildHomeSections(state, settings, pinsShown = true, hasMoods = true).map { it.id }
    val default = ids(base)
    expect(
        "default",
        default == listOf("hero", "quick_picks", "speed_dial", "from_the_community", "forgotten_favorites", "mood_and_genres", "top_songs"),
        default.toString(),
    )
    val arranged = ids(base.copy(homeSectionOrder = listOf("speed_dial", "forgotten_favorites"), homeSectionsHidden = listOf("hero")))
    expect("arranged", arranged.take(3) == listOf("speed_dial", "forgotten_favorites", "quick_picks") && "hero" !in arranged, arranged.toString())
    val noCard = ids(base.copy(homeHeroCard = false))
    expect("card off", "hero" !in noCard, noCard.toString())
    val shuffled = ids(base.copy(homeRandomOrder = true))
    expect("shuffled keeps the card on top", shuffled.first() == "hero" && shuffled.toSet() == default.toSet(), shuffled.toString())
    return ok
}

/** Stats ranks by time listened, as the phone does, and falls back to plays for history without time. */
private fun statsCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} stats $name: $detail")
    }
    val saved = com.convx.desktop.data.Library.value.history
    val artist = com.convx.desktop.data.ArtistRef("UCstats", "Artist")
    val long = com.convx.desktop.data.Song("long", "Long", listOf(artist), com.convx.desktop.data.AlbumRef("MPREstats", "Album"))
    val short = com.convx.desktop.data.Song("short", "Short")
    val old = com.convx.desktop.data.Song("old", "Old")
    val now = System.currentTimeMillis()
    com.convx.desktop.data.Library.applySync { data ->
        data.copy(
            history = listOf(
                com.convx.desktop.data.PlayEvent(short, now - 1_000, playedMs = 30_000),
                com.convx.desktop.data.PlayEvent(short, now - 2_000, playedMs = 30_000),
                com.convx.desktop.data.PlayEvent(long, now - 3_000, playedMs = 240_000),
                com.convx.desktop.data.PlayEvent(old, now - 40L * 86_400_000),
            ),
        )
    }
    val at = com.convx.desktop.data.Library.recordPlay(short, 30_000)
    if (at != null) com.convx.desktop.data.Library.setPlayTime(at, 45_000)
    val week = com.convx.desktop.data.Library.listeningStats(now - 7L * 86_400_000)
    expect("by time", week.songs.map { it.value.id } == listOf("long", "short"), week.songs.map { "${it.value.id} ${it.plays}x ${it.playedMs}ms" }.toString())
    expect("time kept", week.songs.firstOrNull { it.value.id == "short" }?.playedMs == 105_000L, week.songs.toString())
    expect("artists and albums", week.artists.map { it.value.id } == listOf("UCstats") && week.albums.map { it.value.id } == listOf("MPREstats"), "${week.artists} ${week.albums}")
    val all = com.convx.desktop.data.Library.listeningStats(0)
    expect("all time", all.songs.size == 3 && all.songs.last().value.id == "old", all.songs.map { it.value.id }.toString())
    com.convx.desktop.data.Library.applySync { it.copy(history = saved) }
    return ok
}

/**
 * The lyrics menu's offline parts: lyrics kept as text and read back with their timing, a
 * translation read while it streams in, and Claude's stream read as the translator reads it.
 */
private fun lyricsMenuCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} lyrics menu $name: $detail")
    }
    val word = { start: Long, end: Long, text: String -> com.convx.music.lyrics.bitchord.LyricWord(start, end, text) }
    val timed = listOf(
        com.convx.music.lyrics.bitchord.LyricLine(12_340, "Hello there", listOf(word(12_340, 12_800, "Hello"), word(12_900, 13_500, "there"))),
        com.convx.music.lyrics.bitchord.LyricLine(15_000, "Again", listOf(word(15_000, 15_700, "Again"))),
    )
    val text = timed.toLyricsText()
    // The parser gives a long intro its own blank break, as it does for fetched lyrics.
    val back = com.convx.desktop.lyrics.parseLyricsText(text).filter { it.text.isNotBlank() }
    expect(
        "word timing kept",
        back.map { it.text } == listOf("Hello there", "Again") && back.first().words.map { it.startMs } == listOf(12_340L, 12_900L),
        back.joinToString { "${it.timeMs} ${it.text} ${it.words.map { w -> w.startMs }}" },
    )
    val plain = com.convx.desktop.lyrics.parseLyricsText("First line\nSecond line")
    expect("plain text stays plain", plain.size == 2 && plain.all { it.timeMs == 0L }, plain.joinToString { it.text })

    val partial = com.convx.desktop.lyrics.LyricsTranslator.partialLines("""Sure: ["Hola", "Dijo \"ya\"", "Otra""", 5)
    expect("partial translation", partial == listOf("Hola", "Dijo \"ya\""), partial.toString())
    val lyrics = com.convx.desktop.lyrics.Lyrics(
        listOf("One", "", "Two").map { com.convx.music.lyrics.bitchord.LyricLine(0, it) },
        "Check",
    )
    val aligned = com.convx.desktop.lyrics.LyricsTranslator.align(lyrics, listOf("Uno", "Dos"))
    expect("translation skips gaps", aligned == listOf("Uno", null, "Dos"), aligned.toString())

    val claude = com.convx.desktop.lyrics.ClaudeTranslator
    val sse = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","type":"message"}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"[\"Uno\", "}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"\"Dos\"]"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

        event: message_stop
        data: {"type":"message_stop"}
    """.trimIndent()
    var pieces = 0
    val streamed = claude.read(sse.lineSequence()) { pieces++ }
    val result = claude.result(streamed, 2)
    expect("Claude stream", result.getOrNull() == listOf("Uno", "Dos") && pieces == 2 && streamed.stopReason == "end_turn", "${result.getOrNull()} after $pieces pieces, ${streamed.stopReason}")
    val refused = claude.result(claude.read(sequenceOf("""data: {"type":"message_delta","delta":{"stop_reason":"refusal"}}""")) {}, 2)
    expect("Claude refusal", refused.isFailure, refused.exceptionOrNull()?.message.orEmpty())
    return ok
}

/** The phone's M3U and CSV playlist imports, and what this reads that the phone doesn't. */
private fun playlistFileCheck(): Boolean {
    var ok = true
    fun expect(name: String, pass: Boolean, detail: String) {
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} playlist file $name: $detail")
    }
    val files = com.convx.desktop.integrations.PlaylistFiles
    val m3u = files.parseM3u(
        """
        #EXTM3U
        #PLAYLIST:Road trip
        #EXTINF:354,Queen - Bohemian Rhapsody
        C:\Music\Queen - Bohemian Rhapsody.mp3
        #EXTINF:-1 tvg-name="a,b",Daft Punk;Pharrell Williams - Get Lucky
        https://music.youtube.com/watch?v=5NV6Rdv1a3I&si=abc
        D:\Music\01 - Radiohead - Creep.flac
        https://radio.example.com/stream
        #EXTINF:200,Adele - Hello
        """.trimIndent(),
        "fallback",
    )
    expect("m3u name", m3u.title == "Road trip", m3u.title)
    expect(
        "m3u songs",
        m3u.tracks.map { it.title } == listOf("Bohemian Rhapsody", "Get Lucky", "Creep", "Hello") &&
            m3u.tracks[0].durationMs == 354_000L && m3u.tracks[0].artists == listOf("Queen") &&
            m3u.tracks[1].artists == listOf("Daft Punk", "Pharrell Williams") && m3u.tracks[1].videoId == "5NV6Rdv1a3I" &&
            m3u.tracks[2].artists == listOf("Radiohead"),
        "${m3u.tracks}",
    )
    val exportify = "\uFEFF\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\r\n" +
        "\"spotify:track:1\",\"Bohemian Rhapsody\",\"Queen\",\"A Night at the Opera\",\"354320\"\r\n" +
        "\"spotify:track:2\",\"Get Lucky\",\"Daft Punk,Pharrell Williams\",\"Random Access Memories\",\"369626\"\r\n" +
        "\"spotify:track:3\",\"Say \"\"Hello\"\", again\",\"Someone; Other\",\"X\",\"215000\"\r\n"
    val text = exportify.removePrefix("\uFEFF")
    val preview = files.previewCsv(text)
    expect(
        "csv columns",
        preview.hasHeader && preview.titleColumn == 1 && preview.artistColumn == 2 && preview.durationColumn == 4 && preview.urlColumn == -1,
        "$preview",
    )
    val csv = files.parseCsvTracks(text, com.convx.desktop.integrations.PlaylistFiles.CsvMapping(true, preview.artistColumn, preview.titleColumn, preview.urlColumn, preview.durationColumn))
    expect(
        "csv songs",
        csv.map { it.title } == listOf("Bohemian Rhapsody", "Get Lucky", "Say \"Hello\", again") &&
            csv[1].artists == listOf("Daft Punk", "Pharrell Williams") && csv[2].artists == listOf("Someone", "Other") && csv[0].durationMs == 354_320L,
        "${csv}",
    )
    val plain = files.previewCsv("Queen;Bohemian Rhapsody\nAdele;Hello\n")
    val plainTracks = files.parseCsvTracks("Queen;Bohemian Rhapsody\nAdele;Hello\n", com.convx.desktop.integrations.PlaylistFiles.CsvMapping(plain.hasHeader, plain.artistColumn, plain.titleColumn))
    expect("csv without header", !plain.hasHeader && plainTracks.map { it.title } == listOf("Bohemian Rhapsody", "Hello"), "${plainTracks}")
    // iTunes' "Export Playlist" as text: UTF-16 with a byte order mark, tab separated, seconds.
    val itunes = java.io.File.createTempFile("convx-itunes", ".txt")
    try {
        itunes.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Name\tArtist\tAlbum\tTime\r\nHello\tAdele\t25\t295\r\n".toByteArray(Charsets.UTF_16LE))
        val itunesText = files.read(itunes)
        val itunesPreview = files.previewCsv(itunesText)
        val itunesTracks = files.parseCsvTracks(
            itunesText,
            com.convx.desktop.integrations.PlaylistFiles.CsvMapping(itunesPreview.hasHeader, itunesPreview.artistColumn, itunesPreview.titleColumn, itunesPreview.urlColumn, itunesPreview.durationColumn),
        )
        expect(
            "itunes text export",
            itunesTracks.size == 1 && itunesTracks[0].title == "Hello" && itunesTracks[0].artists == listOf("Adele") && itunesTracks[0].durationMs == 295_000L,
            "$itunesPreview -> $itunesTracks",
        )
        itunes.writeBytes("#EXTINF:120,Beyonc\u00e9 - Halo\r\n".toByteArray(charset("windows-1252")))
        expect("ansi m3u", files.parseM3u(files.read(itunes), "x").tracks.singleOrNull()?.artists == listOf("Beyonc\u00e9"), files.read(itunes))
    } finally {
        itunes.delete()
    }
    expect(
        "youtube links",
        files.youTubeId("https://youtu.be/dQw4w9WgXcQ?t=1") == "dQw4w9WgXcQ" && files.youTubeId("dQw4w9WgXcQ") == "dQw4w9WgXcQ" &&
            files.youTubeId("https://example.com/watch?v=dQw4w9WgXcQ") == null,
        "ok",
    )
    return ok
}

/**
 * Creates a party on the phone's real server, joins it as a second device, and checks a song set
 * by one reaches the other. The free server can be asleep or busy, so this only warns.
 */
private fun partyServerCheck() {
    val server = com.convx.desktop.integrations.party.Party.DefaultServer
    val client = com.convx.desktop.integrations.party.PartyClient
    fun request(name: String) = com.convx.desktop.integrations.party.JoinRequest(
        userId = java.util.UUID.randomUUID().toString(),
        deviceId = java.util.UUID.randomUUID().toString(),
        displayName = name,
    )
    fun socket(code: String, token: String, frames: kotlinx.coroutines.channels.Channel<kotlinx.serialization.json.JsonObject>) =
        client.open(server, code, token, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                runCatching { frames.trySend(kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject) }
            }
        })
    suspend fun kotlinx.coroutines.channels.Channel<kotlinx.serialization.json.JsonObject>.awaitFrame(
        what: (kotlinx.serialization.json.JsonObject) -> Boolean,
    ): kotlinx.serialization.json.JsonObject {
        while (true) {
            val frame = receive()
            if (what(frame)) return frame
        }
    }
    fun type(frame: kotlinx.serialization.json.JsonObject) = (frame["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    fun trackOf(frame: kotlinx.serialization.json.JsonObject, key: String): String? = runCatching {
        val holder = frame[key] as kotlinx.serialization.json.JsonObject
        val playback = (holder["playback"] ?: holder) as kotlinx.serialization.json.JsonObject
        ((playback["track"] as kotlinx.serialization.json.JsonObject)["videoId"] as kotlinx.serialization.json.JsonPrimitive).content
    }.getOrNull()

    runCatching {
        runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(120_000) {
                client.warm(server)
                val host = client.create(server, request("Convx CI host"))
                println("PASS party server create: code ${host.code}, host=${host.you.isHost}")
                val guest = client.join(server, host.code, request("Convx CI guest"))
                println("PASS party server join: ${guest.party.members.size} members, guest host=${guest.you.isHost}")
                val hostFrames = kotlinx.coroutines.channels.Channel<kotlinx.serialization.json.JsonObject>(64)
                val guestFrames = kotlinx.coroutines.channels.Channel<kotlinx.serialization.json.JsonObject>(64)
                val hostSocket = socket(host.code, host.token, hostFrames)
                val guestSocket = socket(guest.code, guest.token, guestFrames)
                try {
                    hostSocket.send("{\"type\":\"sync\"}")
                    hostFrames.awaitFrame { type(it) == "welcome" || type(it) == "state" }
                    hostSocket.send("{\"type\":\"ping\",\"clientMs\":${System.currentTimeMillis()}}")
                    val pong = hostFrames.awaitFrame { type(it) == "pong" }
                    println("PASS party server clock: pong with serverMs ${pong["serverMs"]}")
                    hostSocket.send(
                        "{\"type\":\"control\",\"action\":\"setTrack\",\"track\":{\"videoId\":\"4NRXx6U8ABQ\",\"title\":\"Blinding Lights\"," +
                            "\"artist\":\"The Weeknd\",\"durationMs\":200000},\"positionMs\":0,\"isPlaying\":true}",
                    )
                    guestSocket.send("{\"type\":\"sync\"}")
                    val seen = guestFrames.awaitFrame { trackOf(it, "playback") == "4NRXx6U8ABQ" || trackOf(it, "party") == "4NRXx6U8ABQ" }
                    println("PASS party server sync: the guest got the host's song in a ${type(seen)} frame")
                } finally {
                    hostSocket.close(1000, null)
                    guestSocket.close(1000, null)
                    client.leave(server, guest.code, guest.token)
                    client.leave(server, host.code, host.token)
                }
            }
        }
    }.onFailure { println("WARN party server: ${it::class.simpleName}: ${it.message}") }
}

private fun tryPlay(id: String): String {
    val started = System.currentTimeMillis()
    val stream = runBlocking(Dispatchers.IO) { StreamResolver.resolve(id, AudioQuality.AUTO) }.getOrThrow()
    val resolvedMs = System.currentTimeMillis() - started

    var input: GrowingFileInputStream? = null
    val grabber = when (val source = AudioCache.open(stream)) {
        is CachedAudio.Complete -> FFmpegFrameGrabber(source.file.absolutePath)
        is CachedAudio.Growing -> FFmpegFrameGrabber(GrowingFileInputStream(source.download).also { input = it })
    }
    try {
        grabber.sampleMode = FrameGrabber.SampleMode.SHORT
        grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
        grabber.sampleRate = 48_000
        grabber.audioChannels = 2
        grabber.start()
        var samples = 0L
        var firstAudioMs = -1L
        while (samples < 48_000L * 10) {
            val frame = grabber.grabSamples() ?: break
            val buffer = frame.samples?.firstOrNull() ?: continue
            if (firstAudioMs < 0) firstAudioMs = System.currentTimeMillis() - started
            samples += (buffer.limit() - buffer.position()) / 2
        }
        check(samples >= 48_000L * 5) { "only decoded ${samples / 48} ms" }

        // Jump ahead the way a click on the seek bar does.
        val seekStarted = System.currentTimeMillis()
        grabber.timestamp = 60_000_000L
        val afterSeek = grabber.grabSamples()?.samples?.firstOrNull()
        checkNotNull(afterSeek) { "nothing after seeking to 1:00" }
        val seekMs = System.currentTimeMillis() - seekStarted

        return "${stream.badge} via ${stream.client}, resolved in ${resolvedMs}ms, first audio at ${firstAudioMs}ms, " +
            "seek to 1:00 took ${seekMs}ms, length ${grabber.lengthInTime / 1_000_000}s"
    } finally {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
        runCatching { input?.close() }
    }
}

/**
 * The sound effects on made-up signals, where the right answer is known: a band boosts its own
 * frequency and leaves others alone, spatial audio stays in range, and skip silence cuts a
 * second of silence down to its padding. Prints one line each; false if any is off.
 */
private fun dspCheck(): Boolean {
    val rate = 48_000
    fun sine(hz: Double, seconds: Double, amplitude: Float = 0.25f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i -> (amplitude * kotlin.math.sin(2 * Math.PI * hz * (i / 2) / rate)).toFloat() }
    }
    fun rms(buffer: FloatArray, from: Int, frames: Int): Double {
        var sum = 0.0
        for (i in from * 2 until (from + frames) * 2) sum += buffer[i] * buffer[i].toDouble()
        return kotlin.math.sqrt(sum / (frames * 2))
    }
    /** How much [effects] multiply a [hz] tone, measured after the filters settle. */
    fun gainAt(hz: Double, effects: AudioEffects): Double {
        val input = sine(hz, 1.0)
        val reference = rms(input, rate / 2, rate / 2)
        val chain = EffectsChain(rate)
        chain.configure(effects)
        val n = chain.process(input.copyOf(), rate)
        return rms(chain.output, n / 2, n / 2) / reference
    }
    var ok = true
    fun expect(name: String, value: Double, range: ClosedFloatingPointRange<Double>) {
        val pass = value.isFinite() && value in range
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} dsp $name = ${"%.3f".format(value)} (want ${range.start}..${range.endInclusive})")
    }

    val peak = AudioEffects(eq = listOf(EqBand(1000.0, 6.0, 1.41)))
    expect("+6 dB at 1 kHz, on 1 kHz", gainAt(1000.0, peak), 1.85..2.15)
    expect("+6 dB at 1 kHz, on 100 Hz", gainAt(100.0, peak), 0.95..1.08)
    val shelf = AudioEffects(eq = listOf(EqBand(105.0, 6.0, 0.7, EqFilter.LSC)))
    expect("+6 dB low shelf, on 30 Hz", gainAt(30.0, shelf), 1.8..2.15)
    expect("+6 dB low shelf, on 5 kHz", gainAt(5000.0, shelf), 0.95..1.06)
    val (bands, preamp) = Equalizer.fromGains(Equalizer.Groups[0].presets[4].gains)
    expect("Bass Boost with its preamp, on 31 Hz", gainAt(31.0, AudioEffects(eq = bands, preamp = preamp)), 1.1..1.35)
    expect("spatial audio, on a centred tone", gainAt(440.0, AudioEffects(spatial = true)), 0.65..0.78)
    // Vocal reduction turns down what's in the middle and leaves a tone on one side alone. It
    // runs a window behind, so it's fed in player-sized blocks and measured in the second half.
    fun reduced(input: FloatArray): Double {
        val chain = EffectsChain(rate).apply { configure(AudioEffects(vocalReduction = 1f)) }
        val out = FloatArray(input.size)
        var at = 0
        while (at < rate) {
            val frames = minOf(1024, rate - at)
            val n = chain.process(input.copyOfRange(at * 2, (at + frames) * 2), frames)
            System.arraycopy(chain.output, 0, out, at * 2, n * 2)
            at += frames
        }
        return rms(out, rate / 2, rate / 2) / rms(input, rate / 2, rate / 2)
    }
    expect("vocal reduction, on a centred tone", reduced(sine(440.0, 1.0)), 0.0..0.3)
    expect("vocal reduction, on a tone on one side", reduced(sine(440.0, 1.0).also { for (i in 1 until it.size step 2) it[i] = 0f }), 0.85..1.15)

    // One second of tone, one of silence, one of tone: the silence should shrink to its padding.
    val tone = sine(440.0, 1.0)
    val signal = tone + FloatArray(rate * 2) + tone
    val skipper = EffectsChain(rate).apply { configure(AudioEffects(skipSilence = true)) }
    var kept = 0
    var dropped = 0
    var at = 0
    while (at < rate * 3) {
        val frames = minOf(1024, rate * 3 - at)
        kept += skipper.process(signal.copyOfRange(at * 2, (at + frames) * 2), frames)
        dropped += skipper.skippedFrames
        at += frames
    }
    expect("skip silence, seconds kept of 3", kept / rate.toDouble(), 2.0..2.1)
    expect("skip silence, frames accounted for", (kept + dropped) / rate.toDouble(), 2.999..3.001)
    expect("soft clip of 2.0", softClip(2f).toDouble(), 0.95..1.0)

    val parsed = runCatching {
        ParametricEq.parse("Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70\nFilter 2: ON PK Fc 2500 Hz Gain -3.1 dB Q 1.20\nFilter 3: OFF PK Fc 9000 Hz Gain 2 dB Q 2")
    }
    expect("ParametricEQ filters read", parsed.getOrNull()?.bands?.size?.toDouble() ?: -1.0, 2.0..2.0)
    expect("ParametricEQ preamp", parsed.getOrNull()?.preamp ?: 0.0, -6.21..-6.19)
    return ok
}
