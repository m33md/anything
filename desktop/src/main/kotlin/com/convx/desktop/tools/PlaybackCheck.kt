/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.tools

import com.convx.desktop.core.Log
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.AudioQuality
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
    exitProcess(if (passed > 0) 0 else 1)
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
