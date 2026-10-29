/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.HeroColors
import com.convx.desktop.ui.theme.MeshColors
import com.convx.desktop.ui.theme.Palette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Resizes YouTube/Google artwork URLs to the size actually drawn (the phone's String.resize). */
fun artworkUrl(url: String?, size: Int): String? {
    if (url.isNullOrBlank()) return null
    if (url.contains("i.ytimg.com")) {
        val quality = when {
            size >= 1000 -> "maxresdefault.jpg"
            size >= 400 -> "sddefault.jpg"
            size >= 250 -> "hqdefault.jpg"
            else -> "mqdefault.jpg"
        }
        return url.replace(Regex("""[a-z0-9_]+default\.(jpg|webp)"""), quality).substringBefore("?")
    }
    if ((url.contains("googleusercontent.com") || url.contains("ggpht.com")) && url.contains("=w")) {
        return "${url.substringBefore("=w")}=w$size-h$size-p-l90-rj"
    }
    if ((url.contains("googleusercontent.com") || url.contains("ggpht.com")) && Regex("""=s\d+""").containsMatchIn(url)) {
        return url.replace(Regex("""=s\d+.*$"""), "=s$size")
    }
    return url
}

/**
 * Memory + disk image cache. Thumbnails are small and YouTube serves them fast, but
 * scrolling a long shelf asks for dozens at once, so network fetches are throttled and
 * duplicate requests share one download.
 */
object ImageCache {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 400
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val palettes = ConcurrentHashMap<String, HeroColors>()
    private val meshes = ConcurrentHashMap<String, List<Color>>()
    private val network = Semaphore(8)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun peek(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        if (url in failed) return null
        val mine = CompletableDeferred<ImageBitmap?>()
        val existing = inFlight.putIfAbsent(url, mine)
        if (existing != null) return existing.await()
        val outcome = runCatching { withContext(Dispatchers.IO) { fetch(url) } }
        val result = outcome.getOrNull()
        if (result != null) synchronized(memory) { memory[url] = result }
        // Only a definite "not there" is remembered; network blips retry on the next ask.
        else if (outcome.isSuccess) failed += url
        mine.complete(result)
        inFlight.remove(url)
        return result
    }

    suspend fun palette(url: String): HeroColors? {
        palettes[url]?.let { return it }
        val bitmap = load(url) ?: return null
        val colors = withContext(Dispatchers.Default) { Palette.extract(bitmap) }
        palettes[url] = colors
        return colors
    }

    suspend fun mesh(url: String): List<Color>? {
        meshes[url]?.let { return it }
        val bitmap = load(url) ?: return null
        val colors = withContext(Dispatchers.Default) { MeshColors.extract(bitmap) }
        meshes[url] = colors
        return colors
    }

    private suspend fun fetch(url: String): ImageBitmap? {
        // Covers of music files on this PC are read where they are.
        if (url.startsWith("file:")) {
            val bytes = File(java.net.URI(url)).takeIf { it.isFile }?.readBytes() ?: return null
            return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }
        val file = File(AppDirs.images, sha1(url))
        val bytes = if (file.isFile && file.length() > 0) {
            file.readBytes()
        } else {
            network.withPermit {
                download(url)
                    // maxres/sd thumbnails don't exist for every video; hq always does.
                    ?: url.takeIf { it.contains("i.ytimg.com") && !it.contains("hqdefault") }
                        ?.let { download(it.replace(Regex("""[a-z0-9_]+default\.jpg"""), "hqdefault.jpg")) }
            }?.also { runCatching { file.writeBytes(it) } } ?: return null
        }
        return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        return Http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.bytes() else null
        }
    }

    fun clearDisk() {
        AppDirs.images.listFiles()?.forEach { it.delete() }
    }

    fun diskBytes(): Long = AppDirs.images.listFiles()?.sumOf { it.length() } ?: 0L

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
fun rememberImage(url: String?): State<ImageBitmap?> =
    produceState(initialValue = url?.let(ImageCache::peek), url) {
        value = url?.let { ImageCache.load(it) }
    }

@Composable
fun rememberHeroColors(url: String?): State<HeroColors?> =
    produceState<HeroColors?>(initialValue = null, url) {
        value = url?.let { ImageCache.palette(it) }
    }

@Composable
fun rememberMeshColors(url: String?): State<List<Color>?> =
    produceState<List<Color>?>(initialValue = null, url) {
        value = url?.let { ImageCache.mesh(it) }
    }

/** Artwork with a quiet placeholder while it loads (or when there is none). */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 544,
    shape: Shape = ConvxShapes.Artwork,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderIcon: String = "music_note",
    iconSize: Dp = 28.dp,
) {
    val resolved = artworkUrl(url, size)
    val image by rememberImage(resolved)
    Box(modifier.clip(shape).background(ConvxColors.CardSecondary), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            ConvxIcon(placeholderIcon, tint = Color.White.copy(alpha = 0.28f), size = iconSize)
        }
    }
}
