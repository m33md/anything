/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs related to the ones you play, kept as the phone keeps its related-songs table: looked up
 * once when a song first plays, so Home's Quick picks come straight from disk instead of waiting
 * on YouTube. One small file per song, so playing doesn't rewrite a big one.
 */
object RelatedSongs {
    private const val Tag = "RelatedSongs"
    private const val Kept = 3000
    private val dir by lazy { File(AppDirs.data, "related").apply { mkdirs() } }
    private val serializer = ListSerializer(Song.serializer())
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val videoId = Regex("[A-Za-z0-9_-]{6,20}")

    private fun file(id: String): File? = if (videoId.matches(id)) File(dir, "$id.json") else null

    fun get(id: String): List<Song>? = file(id)?.takeIf { it.isFile }?.let { file ->
        runCatching { StoreJson.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    /** The phone's hasRelatedSongs check, then its look-up, in the background, when [id] starts playing. */
    fun ensure(id: String) {
        val file = file(id) ?: return
        if (file.isFile || !asking.add(id)) return
        AppScope.launch(Dispatchers.IO) {
            try {
                fetch(id)
            } finally {
                asking.remove(id)
            }
        }
    }

    /** Asks YouTube for [id]'s related songs and keeps them; null if it couldn't. */
    suspend fun fetch(id: String): List<Song>? {
        val file = file(id) ?: return null
        val endpoint = YouTube.next(WatchEndpoint(videoId = id)).getOrNull()?.relatedEndpoint ?: return null
        val songs = YouTube.related(endpoint).getOrNull()?.songs?.map { it.toSong() } ?: return null
        runCatching {
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(StoreJson.encodeToString(serializer, songs))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.w(Tag, "Couldn't keep related songs for $id", it) }
        prune()
        return songs
    }

    /** Keeps the newest few thousand, so the folder doesn't grow forever. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= Kept + 100) return
        files.sortedBy { it.lastModified() }.take(files.size - Kept).forEach { it.delete() }
    }
}
