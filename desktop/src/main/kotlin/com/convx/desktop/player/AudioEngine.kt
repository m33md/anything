/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.Log
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Decodes with FFmpeg and plays through Java Sound. One [Session] (a thread) per loaded
 * track; play/pause/seek act on the live session. Everything YouTube serves (Opus in WebM,
 * AAC in MP4) decodes the same way, so there is no codec matrix to manage.
 */
class AudioEngine(private val listener: Listener) {
    interface Listener {
        fun onReady(token: Long, durationMs: Long)
        fun onEnded(token: Long)
        fun onError(token: Long, error: Throwable, positionMs: Long)
    }

    companion object {
        private const val Tag = "AudioEngine"
        private const val SampleRate = 48_000
        private const val Channels = 2
        private const val BufferMs = 250

        /** Loads the native libraries early so the first song doesn't pay for it. */
        fun warmUp() {
            thread(name = "convx-ffmpeg-warmup", isDaemon = true) {
                runCatching {
                    FFmpegFrameGrabber.tryLoad()
                    avutil.av_log_set_level(avutil.AV_LOG_ERROR)
                }.onFailure { Log.e(Tag, "FFmpeg failed to load", it) }
            }
        }
    }

    private val tokens = AtomicLong()
    @Volatile private var session: Session? = null

    /** Linear gain, 0..1, applied to every sample. */
    @Volatile var gain: Float = 1f

    /** Loudness normalisation multiplier from the stream's loudnessDb. */
    @Volatile var normalization: Float = 1f

    val positionMs: Long get() = session?.positionMs() ?: 0L
    val durationMs: Long get() = session?.durationMs ?: 0L
    val isPlaying: Boolean get() = session?.let { it.playing && !it.finished } ?: false

    fun load(source: CachedAudio, startMs: Long, playWhenReady: Boolean, durationHintMs: Long?): Long {
        stop()
        val token = tokens.incrementAndGet()
        session = Session(token, source, startMs.coerceAtLeast(0), playWhenReady, durationHintMs ?: 0L).also { it.start() }
        return token
    }

    fun play() {
        session?.setPlayback(true)
    }

    fun pause() {
        session?.setPlayback(false)
    }

    fun seek(positionMs: Long) {
        session?.seek(positionMs.coerceAtLeast(0))
    }

    fun stop() {
        session?.close()
        session = null
    }

    private inner class Session(
        val token: Long,
        private val source: CachedAudio,
        startMs: Long,
        @Volatile var playing: Boolean,
        durationHintMs: Long,
    ) {
        @Volatile var durationMs: Long = durationHintMs
        @Volatile var finished = false
        @Volatile private var closed = false
        @Volatile private var pendingSeekMs: Long = -1
        @Volatile private var baseMs: Long = startMs
        @Volatile private var framesAtBase: Long = 0
        @Volatile private var line: SourceDataLine? = null
        private val signal = Object()

        fun start() {
            thread(name = "convx-audio-$token", isDaemon = true) { run() }
        }

        fun positionMs(): Long {
            val l = line ?: return baseMs
            if (pendingSeekMs >= 0) return pendingSeekMs
            val played = (l.longFramePosition - framesAtBase).coerceAtLeast(0)
            val pos = baseMs + played * 1000 / SampleRate
            return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
        }

        fun setPlayback(value: Boolean) {
            playing = value
            line?.let { if (value) it.start() else it.stop() }
            synchronized(signal) { signal.notifyAll() }
        }

        fun seek(ms: Long) {
            pendingSeekMs = ms
            line?.flush()
            synchronized(signal) { signal.notifyAll() }
        }

        fun close() {
            closed = true
            line?.let {
                it.stop()
                it.flush()
            }
            synchronized(signal) { signal.notifyAll() }
        }

        private fun run() {
            var grabber: FFmpegFrameGrabber? = null
            var input: GrowingFileInputStream? = null
            try {
                grabber = when (source) {
                    is CachedAudio.Complete -> FFmpegFrameGrabber(source.file.absolutePath)
                    is CachedAudio.Growing -> FFmpegFrameGrabber(GrowingFileInputStream(source.download).also { input = it })
                }
                grabber.sampleMode = FrameGrabber.SampleMode.SHORT
                grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
                grabber.sampleRate = SampleRate
                grabber.audioChannels = Channels
                grabber.start()
                if (closed) return
                grabber.lengthInTime.takeIf { it > 0 }?.let { durationMs = it / 1000 }
                if (baseMs > 0) grabber.timestamp = baseMs * 1000

                val format = AudioFormat(SampleRate.toFloat(), 16, Channels, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, SampleRate * Channels * 2 * BufferMs / 1000)
                framesAtBase = l.longFramePosition
                line = l
                if (closed) return
                if (playing) l.start()
                listener.onReady(token, durationMs)

                var bytes = ByteArray(0)
                while (!closed) {
                    val seekTo = pendingSeekMs
                    if (seekTo >= 0) {
                        l.flush()
                        grabber.timestamp = seekTo * 1000
                        baseMs = seekTo
                        framesAtBase = l.longFramePosition
                        pendingSeekMs = -1
                        if (playing) l.start()
                    }
                    if (!playing) {
                        synchronized(signal) { signal.wait(100) }
                        continue
                    }
                    val frame = grabber.grabSamples()
                    if (frame == null) {
                        // End of stream: let the buffer play out unless we were interrupted.
                        if (pendingSeekMs >= 0) continue
                        l.drain()
                        if (pendingSeekMs >= 0) continue
                        finished = true
                        break
                    }
                    val samples = frame.samples ?: continue
                    val count = interleavedCount(samples)
                    if (bytes.size < count * 2) bytes = ByteArray(count * 2)
                    fill(samples, bytes, gain * normalization)
                    var offset = 0
                    while (offset < count * 2 && !closed && pendingSeekMs < 0) {
                        val written = l.write(bytes, offset, count * 2 - offset)
                        if (written <= 0 && !playing) synchronized(signal) { signal.wait(50) }
                        offset += written
                    }
                }
            } catch (e: Throwable) {
                if (!closed) {
                    Log.e(Tag, "Playback failed", e)
                    listener.onError(token, e, positionMs())
                }
                return
            } finally {
                runCatching { line?.close() }
                runCatching { grabber?.stop() }
                runCatching { grabber?.release() }
                runCatching { input?.close() }
            }
            if (finished && !closed) listener.onEnded(token)
        }

        private fun interleavedCount(samples: Array<java.nio.Buffer>): Int =
            if (samples.size == 1) samples[0].limit() - samples[0].position()
            else (samples[0].limit() - samples[0].position()) * samples.size

        /** Writes 16-bit little-endian PCM with gain into [out]; handles packed and planar frames. */
        private fun fill(samples: Array<java.nio.Buffer>, out: ByteArray, g: Float) {
            val unity = g in 0.999f..1.001f
            if (samples.size == 1) {
                val buffer = samples[0] as ShortBuffer
                val start = buffer.position()
                val n = buffer.limit() - start
                for (i in 0 until n) {
                    val s = buffer.get(start + i).toInt()
                    val v = if (unity) s else (s * g).toInt().coerceIn(-32768, 32767)
                    out[i * 2] = v.toByte()
                    out[i * 2 + 1] = (v shr 8).toByte()
                }
            } else {
                val planes = samples.map { it as ShortBuffer }
                val perPlane = planes[0].limit() - planes[0].position()
                var o = 0
                for (i in 0 until perPlane) {
                    for (plane in planes) {
                        val s = plane.get(plane.position() + i).toInt()
                        val v = if (unity) s else (s * g).toInt().coerceIn(-32768, 32767)
                        out[o++] = v.toByte()
                        out[o++] = (v shr 8).toByte()
                    }
                }
            }
        }
    }
}
