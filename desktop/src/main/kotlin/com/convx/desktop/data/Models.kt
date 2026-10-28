/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import kotlinx.serialization.Serializable

@Serializable
data class ArtistRef(val id: String? = null, val name: String)

@Serializable
data class AlbumRef(val id: String, val name: String)

/**
 * A track as the desktop app stores and queues it. innertube's own models are not
 * serializable, so everything that reaches the library or the queue becomes one of these.
 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    /** Seconds. */
    val duration: Int? = null,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class SavedAlbum(
    val browseId: String,
    val playlistId: String? = null,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedPlaylist(
    val id: String,
    val title: String,
    val author: String? = null,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedArtist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LikedSong(val song: Song, val likedAt: Long = System.currentTimeMillis())

@Serializable
data class PlayEvent(val song: Song, val at: Long = System.currentTimeMillis())

@Serializable
data class LibraryData(
    val liked: List<LikedSong> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val history: List<PlayEvent> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

enum class RepeatMode { OFF, ALL, ONE }

enum class AudioQuality { AUTO, HIGH, LOW }

@Serializable
data class Settings(
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** No longer read: Convx is always red on black. Kept so older settings files still read. */
    val accent: Long? = null,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    /** Superseded by [ambientWindow]; kept so older settings files still read. */
    val dynamicBackground: Boolean = true,
    /** No longer read, like [accent]: pages never take a cover's colours. */
    val ambientWindow: Boolean = false,
    val wavyProgress: Boolean = true,
    val sidebarCollapsed: Boolean = false,
    /** Your Library's pinned items, top first, by [libraryKey]. Liked Songs starts pinned, as on Spotify. */
    val pinnedLibrary: List<String> = listOf("liked"),
    /** Your Library's order: recents, added, alphabetical or creator. */
    val librarySort: String = "recents",
    val rightPanel: String? = "nowPlaying",
    val lyricsWordSync: Boolean = true,
    val lyricsProviders: List<String> = emptyList(),
    val hideExplicit: Boolean = false,
    val hideVideos: Boolean = false,
    val autoRadio: Boolean = true,
    val country: String = "",
    val language: String = "",
    val cookie: String? = null,
    /** "Listening to" on Discord, through the Discord app on this PC. Off until connected in Settings. */
    val discordPresence: Boolean = false,
    /** Discord's member list shows the song after "Listening to" instead of the artists. */
    val discordUseDetails: Boolean = false,
    /** The phone's advanced mode: the verb and the two buttons below are only used while it's on. */
    val discordAdvanced: Boolean = false,
    /** listening, playing, watching or competing. */
    val discordActivityType: String = "listening",
    val discordButton1: Boolean = true,
    /** Blank for "Listen on Convx"; {song_name}, {artist_name} and {album_name} are filled in. */
    val discordButton1Text: String = "",
    val discordButton2: Boolean = true,
    /** Blank for "Visit Convx". */
    val discordButton2Text: String = "",
    /** YouTube's guest session id; kept so recommendations stay consistent between launches. */
    val visitorData: String? = null,
    val lastQueue: List<Song> = emptyList(),
    val lastIndex: Int = 0,
    val lastPositionMs: Long = 0,
    val windowWidth: Int = 1440,
    val windowHeight: Int = 900,
    val windowMaximized: Boolean = false,
)

fun SongItem.toSong(): Song = Song(
    id = id,
    title = title,
    artists = artists.map { ArtistRef(it.id, it.name) },
    album = album?.let { AlbumRef(it.id, it.name) },
    duration = duration,
    thumbnail = thumbnail,
    explicit = explicit,
    isVideo = isVideoSong,
    setVideoId = setVideoId,
)

fun AlbumItem.toSaved(): SavedAlbum = SavedAlbum(
    browseId = browseId,
    playlistId = playlistId,
    title = title,
    artists = artists.orEmpty().map { ArtistRef(it.id, it.name) },
    year = year,
    thumbnail = thumbnail,
)

fun PlaylistItem.toSaved(): SavedPlaylist = SavedPlaylist(
    id = id,
    title = title,
    author = author?.name,
    thumbnail = thumbnail,
)

fun ArtistItem.toSaved(): SavedArtist = SavedArtist(id = id, name = title, thumbnail = thumbnail)
