package com.kolnovel.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kolnovel.reader.data.AppDirs
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.TimeUnit
import org.jetbrains.skia.Image as SkImage

/** Covers: memory cache, then disk cache, then the site. Big covers are shrunk to save memory. */
object ImageLoader {
    private const val MAX_WIDTH = 480
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gate = Semaphore(6)
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(AppDirs.images, sha1(url))
                    val bytes = if (file.exists()) file.readBytes() else download(url)?.also { file.writeBytes(it) }
                    bytes?.let { decode(it) }
                }.getOrNull()?.also { bitmap -> synchronized(memory) { memory[url] = bitmap } }
            }
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url)
            .header("User-Agent", KolSource.USER_AGENT)
            .header("Referer", KolSource.BASE_URL + "/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap {
        val image = SkImage.makeFromEncoded(bytes)
        if (image.width <= MAX_WIDTH) return image.toComposeImageBitmap()
        val w = MAX_WIDTH
        val h = (image.height.toFloat() * w / image.width).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            null,
            true,
        )
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    val bitmap by produceState(url?.let { ImageLoader.cached(it) }, url) {
        if (url != null && value == null) value = ImageLoader.load(url)
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(LocalAppColors.current.glassFill)) { placeholder() }
    }
}
