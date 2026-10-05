package com.kolnovel.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kolnovel.reader.data.AppDirs
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.WebViewCookieJar
import com.kolnovel.reader.data.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Covers: memory cache, then disk cache, then the site. Big covers are shrunk to save memory. */
object ImageLoader {
    private const val MAX_WIDTH = 360
    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gate = Semaphore(6)
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 150
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

    /** Decodes at a reduced size (power of two) so big covers don't eat the phone's memory. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    /** Fetches a cover into the disk cache without decoding it (used when saving a novel for offline). */
    suspend fun prefetch(url: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(AppDirs.images, sha1(url))
                if (!file.exists()) download(url)?.let { file.writeBytes(it) }
            }
        }
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
