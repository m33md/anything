/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SpotifyProfile(val id: String, val name: String, val image: String?, val premium: Boolean)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val image: String?,
    val trackCount: Int,
    val description: String = "",
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val image: String?,
    val durationMs: Long,
    val explicit: Boolean,
)

/**
 * Spotify's Web API, read-only: who you are, your playlists, what's in them, and your Liked
 * Songs. Pages are fetched whole and kept for the session; [clearCache] drops them.
 */
object SpotifyApi {
    private const val Tag = "Spotify"
    private const val Base = "https://api.spotify.com/v1"
    private val json = Json { ignoreUnknownKeys = true }

    private val playlistTracks = ConcurrentHashMap<String, List<SpotifyTrack>>()
    @Volatile private var playlists: List<SpotifyPlaylist>? = null
    @Volatile private var liked: List<SpotifyTrack>? = null

    fun clearCache() {
        playlistTracks.clear()
        playlists = null
        liked = null
    }

    suspend fun me(): Result<SpotifyProfile> = runCatching {
        val obj = get("$Base/me")
        SpotifyProfile(
            id = obj.str("id").orEmpty(),
            name = obj.str("display_name")?.takeIf { it.isNotBlank() } ?: obj.str("id").orEmpty(),
            image = obj.arr("images")?.let(::bestImage),
            premium = obj.str("product") == "premium",
        )
    }

    suspend fun playlists(refresh: Boolean = false): Result<List<SpotifyPlaylist>> = runCatching {
        if (!refresh) playlists?.let { return@runCatching it }
        val items = pages("$Base/me/playlists?limit=50").mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            // Spotify renamed "tracks" to "items" on playlists; read whichever is there.
            val count = (obj.obj("items") ?: obj.obj("tracks"))?.int("total") ?: 0
            SpotifyPlaylist(
                id = id,
                name = obj.str("name").orEmpty(),
                owner = obj.obj("owner")?.str("display_name").orEmpty(),
                image = obj.arr("images")?.let(::bestImage),
                trackCount = count,
                description = obj.str("description").orEmpty(),
            )
        }
        playlists = items
        items
    }

    suspend fun playlistTracks(id: String, refresh: Boolean = false): Result<List<SpotifyTrack>> = runCatching {
        if (!refresh) playlistTracks[id]?.let { return@runCatching it }
        val fields = "limit=100&additional_types=track&market=from_token"
        val rows = try {
            pages("$Base/playlists/$id/items?$fields")
        } catch (e: SpotifyException) {
            if (e.status != 404 && e.status != 410) throw e
            pages("$Base/playlists/$id/tracks?$fields")
        }
        val tracks = rows.mapNotNull { row -> track((row as? JsonObject)?.let { it.obj("item") ?: it.obj("track") }) }
        playlistTracks[id] = tracks
        tracks
    }

    suspend fun likedSongs(refresh: Boolean = false): Result<List<SpotifyTrack>> = runCatching {
        if (!refresh) liked?.let { return@runCatching it }
        val tracks = pages("$Base/me/tracks?limit=50&market=from_token").mapNotNull { row ->
            track((row as? JsonObject)?.let { it.obj("track") ?: it.obj("item") })
        }
        liked = tracks
        tracks
    }

    private fun track(obj: JsonObject?): SpotifyTrack? {
        obj ?: return null
        // Local files and podcast episodes can't be matched to YouTube Music.
        if (obj.bool("is_local") == true || obj.str("type") == "episode") return null
        val id = obj.str("id") ?: return null
        val album = obj.obj("album")
        return SpotifyTrack(
            id = id,
            title = obj.str("name").orEmpty(),
            artists = obj.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty(),
            album = album?.str("name"),
            image = album?.arr("images")?.let(::bestImage),
            durationMs = obj.long("duration_ms") ?: 0L,
            explicit = obj.bool("explicit") == true,
        )
    }

    /** The largest picture up to 640 px; Spotify lists them largest first. */
    private fun bestImage(images: JsonArray): String? =
        images.mapNotNull { it as? JsonObject }
            .sortedByDescending { it.int("width") ?: 0 }
            .firstOrNull { (it.int("width") ?: 0) <= 640 }
            ?.str("url")
            ?: (images.firstOrNull() as? JsonObject)?.str("url")

    /** Every item of a paged list, following "next" until the end. */
    private suspend fun pages(first: String): List<JsonElement> {
        val all = ArrayList<JsonElement>()
        var url: String? = first
        while (url != null) {
            val page = get(url)
            page.arr("items")?.let(all::addAll)
            url = page.str("next")
            if (all.size > 10_000) break
        }
        return all
    }

    private suspend fun get(url: String, attempt: Int = 0): JsonObject = withContext(Dispatchers.IO) {
        val token = SpotifyAccount.accessToken(forceRefresh = attempt > 0)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", Http.USER_AGENT)
            .build()
        val (code, body, retryAfter) = Http.client.newCall(request).execute().use { response ->
            Triple(response.code, response.body?.string().orEmpty(), response.header("Retry-After")?.toLongOrNull())
        }
        when {
            code in 200..299 -> json.parseToJsonElement(body.ifBlank { "{}" }).jsonObject
            code == 401 && attempt == 0 -> get(url, attempt + 1)
            code == 429 && attempt < 3 -> {
                val wait = (retryAfter ?: 2L).coerceIn(1L, 30L)
                Log.w(Tag, "Spotify asked to slow down; waiting ${wait}s")
                delay(wait * 1000)
                get(url, attempt + 1)
            }
            else -> {
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject.obj("error")?.str("message")
                }.getOrNull()
                throw SpotifyException(code, message?.let { "Spotify: $it" } ?: "Spotify answered $code")
            }
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
    private fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.long(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
