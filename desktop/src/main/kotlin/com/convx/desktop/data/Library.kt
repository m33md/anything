/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** The user's own music: likes, saved albums/playlists/artists, local playlists, history. */
object Library {
    private const val HistoryLimit = 3000
    private const val SearchHistoryLimit = 30

    private val store = JsonFile(File(AppDirs.data, "library.json"), LibraryData.serializer()) { LibraryData() }

    val state: StateFlow<LibraryData> = store.state
    val value: LibraryData get() = store.value

    fun flush() = store.flush()

    // Likes

    fun isLiked(id: String): Boolean = value.liked.any { it.song.id == id }

    fun toggleLike(song: Song) = store.update { data ->
        if (data.liked.any { it.song.id == song.id }) data.copy(liked = data.liked.filterNot { it.song.id == song.id })
        else data.copy(liked = listOf(LikedSong(song)) + data.liked)
    }

    fun setLiked(song: Song, liked: Boolean) {
        if (isLiked(song.id) != liked) toggleLike(song)
    }

    // Saved collections

    fun isAlbumSaved(browseId: String) = value.albums.any { it.browseId == browseId }

    fun toggleAlbum(album: SavedAlbum) = store.update { data ->
        if (data.albums.any { it.browseId == album.browseId }) data.copy(albums = data.albums.filterNot { it.browseId == album.browseId })
        else data.copy(albums = listOf(album) + data.albums)
    }

    fun isPlaylistSaved(id: String) = value.playlists.any { it.id == id }

    fun togglePlaylist(playlist: SavedPlaylist) = store.update { data ->
        if (data.playlists.any { it.id == playlist.id }) data.copy(playlists = data.playlists.filterNot { it.id == playlist.id })
        else data.copy(playlists = listOf(playlist) + data.playlists)
    }

    fun isArtistFollowed(id: String) = value.artists.any { it.id == id }

    fun toggleArtist(artist: SavedArtist) = store.update { data ->
        if (data.artists.any { it.id == artist.id }) data.copy(artists = data.artists.filterNot { it.id == artist.id })
        else data.copy(artists = listOf(artist) + data.artists)
    }

    // Local playlists

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = "local-" + UUID.randomUUID().toString()
        store.update { it.copy(localPlaylists = it.localPlaylists + LocalPlaylist(id, name.ifBlank { "New playlist" }, songs)) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name) }

    fun deletePlaylist(id: String) = store.update { data ->
        data.copy(localPlaylists = data.localPlaylists.filterNot { it.id == id })
    }

    fun addToPlaylist(id: String, songs: List<Song>) = updatePlaylist(id) { playlist ->
        val existing = playlist.songs.mapTo(HashSet()) { it.id }
        playlist.copy(songs = playlist.songs + songs.filterNot { it.id in existing }.distinctBy { it.id })
    }

    fun removeFromPlaylist(id: String, songId: String) = updatePlaylist(id) { playlist ->
        playlist.copy(songs = playlist.songs.filterNot { it.id == songId })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = updatePlaylist(id) { playlist ->
        val songs = playlist.songs.toMutableList()
        if (from !in songs.indices || to !in songs.indices) return@updatePlaylist playlist
        songs.add(to, songs.removeAt(from))
        playlist.copy(songs = songs)
    }

    fun playlist(id: String): LocalPlaylist? = value.localPlaylists.firstOrNull { it.id == id }

    private fun updatePlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) = store.update { data ->
        data.copy(localPlaylists = data.localPlaylists.map {
            if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
        })
    }

    // History

    fun recordPlay(song: Song) = store.update { data ->
        data.copy(history = (listOf(PlayEvent(song)) + data.history).take(HistoryLimit))
    }

    fun clearHistory() = store.update { it.copy(history = emptyList()) }

    fun removeFromHistory(at: Long) = store.update { data -> data.copy(history = data.history.filterNot { it.at == at }) }

    /** Most played songs, newest metadata wins. */
    fun topSongs(limit: Int = 50, since: Long = 0L): List<Pair<Song, Int>> =
        value.history.asSequence()
            .filter { it.at >= since }
            .groupBy { it.song.id }
            .map { (_, plays) -> plays.first().song to plays.size }
            .sortedByDescending { it.second }
            .take(limit)

    /** Recently played, one entry per song. */
    fun recentSongs(limit: Int = 30): List<Song> =
        value.history.asSequence().map { it.song }.distinctBy { it.id }.take(limit).toList()

    // Search history

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.update { data ->
            data.copy(searchHistory = (listOf(q) + data.searchHistory.filterNot { it.equals(q, ignoreCase = true) }).take(SearchHistoryLimit))
        }
    }

    fun removeSearch(query: String) = store.update { data -> data.copy(searchHistory = data.searchHistory - query) }

    fun clearSearches() = store.update { it.copy(searchHistory = emptyList()) }
}

object AppSettings {
    private val store = JsonFile(File(AppDirs.data, "settings.json"), Settings.serializer()) { Settings() }

    val state: StateFlow<Settings> = store.state
    val value: Settings get() = store.value

    fun update(transform: (Settings) -> Settings) = store.update(transform)

    fun flush() = store.flush()
}
