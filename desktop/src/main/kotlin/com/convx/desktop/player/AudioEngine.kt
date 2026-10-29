/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.audio.AudioEffects
import com.convx.desktop.audio.EffectsChain
import com.convx.desktop.audio.softClip
import com.convx.desktop.core.Log
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.nio.ShortBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decodes with FFmpeg and plays through Java Sound. One [Session] (a thread) per loaded
 * track; play/pause/seek act on the live session. Everything YouTube serves (Opus in WebM,
 * AAC in MP4) decodes the same way, so there is no codec matrix to manage.
 *
 * Samples pass through the [effects] (equalizer, spatial audio, skip silence) as floats. For a
 * crossfade the outgoing session is [handOff]ed: it keeps playing on its own line, fading out,
 * while the next one fades in on another, and the system mixes the two.
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
        private val Format = AudioFormat(SampleRate.toFloat(), 16, Channels, true, false)

        /** Loads the native libraries early so the first song doesn't pay for it. */
        fun warmUp() {
            thread(name = "convx-ffmpeg-warmup", isDaemon = true) {
                runCatching {
                    FFmpegFrameGrabber.tryLoad()
                    avutil.av_log_set_level(avutil.AV_LOG_ERROR)
                }.onFailure { Log.e(Tag, "FFmpeg failed to load", it) }
            }
        }

        /** The sound outputs Convx can play through, by the names Windows gives them. */
        fun outputDevices(): List<String> = runCatching {
            val wanted = DataLine.Info(SourceDataLine::class.java, Format)
            AudioSystem.getMixerInfo()
                .filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(wanted) }.getOrDefault(false) }
                .map { it.name }
                // Java's own name for "whatever Windows is using", which is what no choice means.
                .filter { it != "Primary Sound Driver" && !it.startsWith("Default Audio Device") }
                .distinct()
        }.getOrElse {
            Log.e(Tag, "Couldn't list sound outputs", it)
            emptyList()
        }
    }

    private val tokens = AtomicLong()
    @Volatile private var session: Session? = null

    /** Sessions handed off to fade out under the next song. */
    private val fading = CopyOnWriteArraySet<Session>()

    /** Linear gain, 0..1, applied to every sample. */
    @Volatile var gain: Float = 1f

    /** Picked up by every session between buffers, so a change is heard within a few ms. */
    @Volatile var effects: AudioEffects = AudioEffects()

    /** The output to open new sessions on, by name; null for the system's default. */
    @Volatile var outputDevice: String? = null

    val positionMs: Long get() = session?.positionMs() ?: 0L
    val durationMs: Long get() = session?.durationMs ?: 0L
    val isPlaying: Boolean get() = session?.let { it.playing && !it.finished } ?: false

    /**
     * Starts [source] at [startMs]. [trackGain] is the song's loudness normalisation; [fadeInMs]
     * eases it in, for a crossfade.
     */
    fun load(
        source: CachedAudio,
        startMs: Long,
        playWhenReady: Boolean,
        durationHintMs: Long?,
        trackGain: Float = 1f,
        fadeInMs: Int = 0,
    ): Long {
        stop(keepFading = fadeInMs > 0)
        val token = tokens.incrementAndGet()
        session = Session(token, source, startMs.coerceAtLeast(0), playWhenReady, durationHintMs ?: 0L, trackGain, fadeInMs)
            .also { it.start() }
        return token
    }

    /** Lets the current song play on, fading out over [fadeMs], while the next one loads. */
    fun handOff(fadeMs: Int) {
        val current = session ?: return
        session = null
        fading += current
        current.fadeOut(fadeMs)
    }

    fun play() {
        session?.setPlayback(true)
        fading.forEach { it.setPlayback(true) }
    }

    fun pause() {
        session?.setPlayback(false)
        fading.forEach { it.setPlayback(false) }
    }

    fun seek(positionMs: Long) {
        session?.seek(positionMs.coerceAtLeast(0))
    }

    fun stop() = stop(keepFading = false)

    private fun stop(keepFading: Boolean) {
        session?.close()
        session = null
        if (!keepFading) {
            fading.forEach { it.close() }
            fading.clear()
        }
    }

    private inner class Session(
        val token: Long,
        private val source: CachedAudio,
        startMs: Long,
        @Volatile var playing: Boolean,
        durationHintMs: Long,
        private val trackGain: Float,
        fadeInMs: Int,
    ) {
        @Volatile var durationMs: Long = durationHintMs
        @Volatile var finished = false
        @Volatile private var closed = false
        @Volatile private var pendingSeekMs: Long = -1
        @Volatile private var baseMs: Long = startMs
        @Volatile private var framesAtBase: Long = 0
        @Volatile private var line: SourceDataLine? = null
        private val signal = Object()

        private val fadeInFrames = SampleRate.toLong() * fadeInMs / 1000
        private var fadeInDone = 0L
        @Volatile private var fadeOutFrames = 0L
        private var fadeOutDone = 0L

        // Skipped silence, so the playhead (and the lyrics) stay with the music: each mark is the
        // frames written when a stretch was cut, and how many frames the cut left out.
        private val skipMarks = ArrayDeque<LongArray>()
        private var skippedPlayed = 0L
        private var writtenFrames = 0L

        fun start() {
            thread(name = "convx-audio-$token", isDaemon = true) { run() }
        }

        fun positionMs(): Long {
            val l = line ?: return baseMs
            if (pendingSeekMs >= 0) return pendingSeekMs
            val played = (l.longFramePosition - framesAtBase).coerceAtLeast(0)
            val skipped = synchronized(skipMarks) {
                while (skipMarks.isNotEmpty() && skipMarks.first()[0] <= played) skippedPlayed += skipMarks.removeFirst()[1]
                skippedPlayed
            }
            val pos = baseMs + (played + skipped) * 1000 / SampleRate
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

        fun fadeOut(ms: Int) {
            fadeOutFrames = (SampleRate.toLong() * ms / 1000).coerceAtLeast(1)
        }

        fun close() {
            closed = true
            line?.let {
                it.stop()
                it.flush()
            }
            synchronized(signal) { signal.notifyAll() }
        }

        private fun openLine(): SourceDataLine {
            val wanted = outputDevice
            if (wanted != null) {
                val info = AudioSystem.getMixerInfo().firstOrNull { it.name == wanted }
                if (info != null) {
                    runCatching { return AudioSystem.getSourceDataLine(Format, info) }
                        .onFailure { Log.w(Tag, "Couldn't open $wanted, using the default output: ${it.message}") }
                } else {
                    Log.w(Tag, "$wanted isn't connected, using the default output")
                }
            }
            return AudioSystem.getSourceDataLine(Format)
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

                val l = openLine()
                l.open(Format, SampleRate * Channels * 2 * BufferMs / 1000)
                framesAtBase = l.longFramePosition
                line = l
                if (closed) return
                if (playing) l.start()
                listener.onReady(token, durationMs)

                val chain = EffectsChain(SampleRate)
                var floats = FloatArray(0)
                var bytes = ByteArray(0)
                while (!closed) {
                    val seekTo = pendingSeekMs
                    if (seekTo >= 0) {
                        l.flush()
                        grabber.timestamp = seekTo * 1000
                        baseMs = seekTo
                        framesAtBase = l.longFramePosition
                        synchronized(skipMarks) {
                            skipMarks.clear()
                            skippedPlayed = 0
                        }
                        writtenFrames = 0
                        chain.reset()
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
                    val frames = interleavedCount(samples) / Channels
                    if (floats.size < frames * Channels) floats = FloatArray(frames * Channels)
                    decode(samples, floats)

                    val effects = this@AudioEngine.effects
                    chain.configure(effects)
                    val count = chain.process(floats, frames)
                    if (chain.skippedFrames > 0) {
                        synchronized(skipMarks) { skipMarks.addLast(longArrayOf(writtenFrames, chain.skippedFrames.toLong())) }
                    }
                    if (count == 0) continue
                    if (bytes.size < count * Channels * 2) bytes = ByteArray(count * Channels * 2)
                    val shaped = effects.shapesSound || trackGain > 1f
                    if (!encode(chain.output, count, bytes, gain * trackGain, shaped)) {
                        // The fade out is over: this session has handed over to the next song.
                        closed = true
                        break
                    }
                    writtenFrames += count
                    var offset = 0
                    val length = count * Channels * 2
                    while (offset < length && !closed && pendingSeekMs < 0) {
                        val written = l.write(bytes, offset, length - offset)
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
                fading -= this
            }
            if (finished && !closed) listener.onEnded(token)
        }

        private fun interleavedCount(samples: Array<java.nio.Buffer>): Int =
            if (samples.size == 1) samples[0].limit() - samples[0].position()
            else (samples[0].limit() - samples[0].position()) * samples.size

        /** Packed or planar 16-bit frames as interleaved floats in -1..1. */
        private fun decode(samples: Array<java.nio.Buffer>, out: FloatArray) {
            if (samples.size == 1) {
                val buffer = samples[0] as ShortBuffer
                val start = buffer.position()
                val n = buffer.limit() - start
                for (i in 0 until n) out[i] = buffer.get(start + i) / 32768f
            } else {
                val planes = samples.map { it as ShortBuffer }
                val perPlane = planes[0].limit() - planes[0].position()
                var o = 0
                for (i in 0 until perPlane) {
                    for (plane in planes) out[o++] = plane.get(plane.position() + i) / 32768f
                }
            }
        }

        /**
         * Writes [frames] frames as 16-bit little-endian PCM with [g] and any fade, softening
         * peaks when the effects could have pushed them past full scale. False once a fade out
         * has run its course.
         */
        private fun encode(input: FloatArray, frames: Int, out: ByteArray, g: Float, shaped: Boolean): Boolean {
            val fadeOut = fadeOutFrames
            var o = 0
            var i = 0
            repeat(frames) {
                var f = g
                if (fadeInDone < fadeInFrames) {
                    // Equal power: the two songs together stay as loud as one.
                    f *= sin(PI / 2 * fadeInDone / fadeInFrames).toFloat()
                    fadeInDone++
                }
                if (fadeOut > 0) {
                    if (fadeOutDone >= fadeOut) return false
                    f *= cos(PI / 2 * fadeOutDone / fadeOut).toFloat()
                    fadeOutDone++
                }
                repeat(Channels) {
                    var x = input[i++] * f
                    x = if (shaped) softClip(x) else x.coerceIn(-1f, 1f)
                    val v = (x * 32767f).toInt()
                    out[o++] = v.toByte()
                    out[o++] = (v shr 8).toByte()
                }
            }
            return true
        }
    }
}
