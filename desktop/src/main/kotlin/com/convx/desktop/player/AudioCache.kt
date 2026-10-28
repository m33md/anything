/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.music.innertube.models.YouTubeClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Songs are downloaded to disk while they play, in ranged chunks (the phone uses the same
 * trick to avoid YouTube cutting long requests off). Playback reads the growing file, so it
 * starts after the first chunk, seeks anywhere, and a replay needs no network at all.
 */
object AudioCache {
    private const val Tag = "AudioCache"
    private const val ChunkBytes = 1024L * 1024L
    private const val MaxCacheBytes = 1024L * 1024L * 1024L

    val dir: File by lazy { File(AppDirs.cache, "audio").apply { mkdirs() } }

    private val active = ConcurrentHashMap<String, Download>()

    /** A finished download: the data file plus its ".ok" marker (Windows can't rename a file a reader holds open). */
    fun completeFile(videoId: String): File? =
        dir.listFiles { f -> f.name.startsWith("$videoId.") && f.name.endsWith(".ok") }
            ?.firstOrNull()
            ?.let { File(dir, it.name.removeSuffix(".ok")) }
            ?.takeIf { it.isFile }

    fun isCached(videoId: String) = completeFile(videoId) != null

    /** Starts (or joins) the download for [stream] and returns a reader positioned at 0. */
    fun open(stream: ResolvedStream): CachedAudio {
        completeFile(stream.videoId)?.let { file ->
            file.setLastModified(System.currentTimeMillis())
            return CachedAudio.Complete(file)
        }
        val download = active.compute(stream.videoId) { _, existing ->
            if (existing != null && !existing.failed && existing.url == stream.url) existing
            else {
                existing?.cancel()
                Download(stream).also { it.start() }
            }
        }!!
        return CachedAudio.Growing(download)
    }

    /** Warms the next track so the switch is instant. */
    fun prefetch(stream: ResolvedStream) {
        if (isCached(stream.videoId) || active.containsKey(stream.videoId)) return
        active[stream.videoId] = Download(stream).also { it.start() }
    }

    fun cancel(videoId: String) {
        active.remove(videoId)?.cancel()
    }

    /** Drops data files a previous run left unfinished. */
    fun cleanupPartials() {
        dir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".ok") && !File(dir, f.name + ".ok").exists() &&
                !active.containsKey(f.name.substringBefore('.'))
            ) f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        active.values.forEach { it.cancel() }
        active.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".ok") }
            ?.map { File(dir, it.name.removeSuffix(".ok")) }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MaxCacheBytes) break
            if (active.containsKey(file.name.substringBefore('.'))) continue
            total -= file.length()
            File(dir, file.name + ".ok").delete()
            file.delete()
        }
    }

    class Download(stream: ResolvedStream) {
        val videoId = stream.videoId
        val url = stream.url
        val file = File(dir, "${stream.videoId}.${stream.container}")
        private val marker = File(dir, "${stream.videoId}.${stream.container}.ok")
        private val lock = ReentrantLock()
        private val progress = lock.newCondition()

        @Volatile var totalBytes: Long = stream.contentLength
            private set
        @Volatile var downloaded: Long = 0
            private set
        @Volatile var failed = false
            private set
        @Volatile var error: IOException? = null
            private set
        @Volatile private var cancelled = false
        @Volatile var finished = false
            private set

        fun start() {
            thread(name = "convx-download-$videoId", isDaemon = true) { run() }
        }

        fun cancel() {
            cancelled = true
            lock.withLock { progress.signalAll() }
        }

        private fun run() {
            try {
                marker.delete()
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    var position = 0L
                    while (!cancelled) {
                        val known = totalBytes > 0
                        if (known && position >= totalBytes) break
                        val end = if (known) minOf(position + ChunkBytes, totalBytes) - 1 else position + ChunkBytes - 1
                        val request = Request.Builder().url(url)
                            .header("Range", "bytes=$position-$end")
                            .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                            .build()
                        var attempt = 0
                        var read = -1L
                        while (read < 0 && !cancelled) {
                            try {
                                read = fetch(request, out, position)
                            } catch (e: IOException) {
                                if (++attempt >= 3 || e is HttpStatusException) throw e
                                Thread.sleep(500L * attempt)
                            }
                        }
                        if (read <= 0) break
                        position += read
                        lock.withLock {
                            downloaded = position
                            progress.signalAll()
                        }
                        if (!known && read < ChunkBytes) {
                            totalBytes = position
                            break
                        }
                    }
                }
                // A replacement download may already own this file; leave it alone.
                if (cancelled) return
                if (totalBytes <= 0) totalBytes = downloaded
                marker.writeText(totalBytes.toString())
                lock.withLock {
                    finished = true
                    progress.signalAll()
                }
                active.remove(videoId, this)
                trim()
            } catch (e: Exception) {
                Log.w(Tag, "Download of $videoId failed at $downloaded/$totalBytes", e)
                lock.withLock {
                    failed = true
                    error = e as? IOException ?: IOException(e)
                    progress.signalAll()
                }
                active.remove(videoId, this)
            }
        }

        private fun fetch(request: Request, out: RandomAccessFile, position: Long): Long {
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 416) return 0
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                if (totalBytes <= 0) {
                    response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { totalBytes = it }
                }
                val body = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                out.seek(position)
                while (!cancelled) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    // Let readers start before the whole chunk lands.
                    if (written % (256 * 1024) < n) lock.withLock {
                        downloaded = position + written
                        progress.signalAll()
                    }
                }
                return written
            }
        }

        /** Blocks until [position] is readable or the download ends. Returns bytes available from there. */
        fun awaitAvailable(position: Long, timeoutMs: Long = 30_000): Long {
            val deadline = System.currentTimeMillis() + timeoutMs
            lock.withLock {
                while (true) {
                    if (downloaded > position) return downloaded - position
                    if (finished) return 0
                    error?.let { throw it }
                    if (cancelled) throw IOException("Download cancelled")
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("Timed out waiting for audio data")
                    progress.await(minOf(left, 500), java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")
}

/** The source the decoder reads: a finished file, or a download still in flight. */
sealed interface CachedAudio {
    data class Complete(val file: File) : CachedAudio
    data class Growing(val download: AudioCache.Download) : CachedAudio
}

/**
 * An InputStream over a download in progress, with the mark/reset/skip semantics FFmpeg's
 * custom IO uses to seek: mark at 0, reset to 0, skip forward to any offset. Reads past
 * the downloaded edge wait for the bytes instead of reporting end of file.
 */
class GrowingFileInputStream(private val download: AudioCache.Download) : InputStream() {
    private var position = 0L
    private var file: RandomAccessFile? = null

    private fun handle(): RandomAccessFile = file ?: RandomAccessFile(download.file, "r").also { file = it }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val total = download.totalBytes
        if (total > 0 && position >= total) return -1
        val available = download.awaitAvailable(position)
        if (available <= 0) return -1
        val raf = handle()
        raf.seek(position)
        val n = raf.read(b, off, minOf(len.toLong(), available).toInt())
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val total = download.totalBytes
        // Saturating: FFmpeg can ask to skip "to the end" with a huge n.
        val limit = if (total > 0) total else maxOf(download.downloaded, position)
        val target = if (n >= limit - position) limit else position + n
        val skipped = target - position
        position = target
        return skipped
    }

    override fun available(): Int {
        val ahead = download.downloaded - position
        return ahead.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun markSupported() = true
    override fun mark(readlimit: Int) = Unit
    override fun reset() {
        position = 0
    }

    override fun close() {
        file?.close()
        file = null
    }
}
