/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.tools

import com.convx.desktop.audio.AudioEffects
import com.convx.desktop.audio.EffectsChain
import com.convx.desktop.audio.EqBand
import com.convx.desktop.audio.EqFilter
import com.convx.desktop.audio.Equalizer
import com.convx.desktop.audio.ParametricEq
import com.convx.desktop.audio.softClip
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
    val dspOk = dspCheck()
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
    exitProcess(if (passed > 0 && dspOk) 0 else 1)
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

/**
 * The sound effects on made-up signals, where the right answer is known: a band boosts its own
 * frequency and leaves others alone, spatial audio stays in range, and skip silence cuts a
 * second of silence down to its padding. Prints one line each; false if any is off.
 */
private fun dspCheck(): Boolean {
    val rate = 48_000
    fun sine(hz: Double, seconds: Double, amplitude: Float = 0.25f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i -> (amplitude * kotlin.math.sin(2 * Math.PI * hz * (i / 2) / rate)).toFloat() }
    }
    fun rms(buffer: FloatArray, from: Int, frames: Int): Double {
        var sum = 0.0
        for (i in from * 2 until (from + frames) * 2) sum += buffer[i] * buffer[i].toDouble()
        return kotlin.math.sqrt(sum / (frames * 2))
    }
    /** How much [effects] multiply a [hz] tone, measured after the filters settle. */
    fun gainAt(hz: Double, effects: AudioEffects): Double {
        val input = sine(hz, 1.0)
        val reference = rms(input, rate / 2, rate / 2)
        val chain = EffectsChain(rate)
        chain.configure(effects)
        val n = chain.process(input.copyOf(), rate)
        return rms(chain.output, n / 2, n / 2) / reference
    }
    var ok = true
    fun expect(name: String, value: Double, range: ClosedFloatingPointRange<Double>) {
        val pass = value.isFinite() && value in range
        if (!pass) ok = false
        println("${if (pass) "PASS" else "FAIL"} dsp $name = ${"%.3f".format(value)} (want ${range.start}..${range.endInclusive})")
    }

    val peak = AudioEffects(eq = listOf(EqBand(1000.0, 6.0, 1.41)))
    expect("+6 dB at 1 kHz, on 1 kHz", gainAt(1000.0, peak), 1.85..2.15)
    expect("+6 dB at 1 kHz, on 100 Hz", gainAt(100.0, peak), 0.95..1.08)
    val shelf = AudioEffects(eq = listOf(EqBand(105.0, 6.0, 0.7, EqFilter.LSC)))
    expect("+6 dB low shelf, on 30 Hz", gainAt(30.0, shelf), 1.8..2.15)
    expect("+6 dB low shelf, on 5 kHz", gainAt(5000.0, shelf), 0.95..1.06)
    val (bands, preamp) = Equalizer.fromGains(Equalizer.Groups[0].presets[4].gains)
    expect("Bass Boost with its preamp, on 31 Hz", gainAt(31.0, AudioEffects(eq = bands, preamp = preamp)), 1.1..1.35)
    expect("spatial audio, on a centred tone", gainAt(440.0, AudioEffects(spatial = true)), 0.65..0.78)

    // One second of tone, one of silence, one of tone: the silence should shrink to its padding.
    val tone = sine(440.0, 1.0)
    val signal = tone + FloatArray(rate * 2) + tone
    val skipper = EffectsChain(rate).apply { configure(AudioEffects(skipSilence = true)) }
    var kept = 0
    var dropped = 0
    var at = 0
    while (at < rate * 3) {
        val frames = minOf(1024, rate * 3 - at)
        kept += skipper.process(signal.copyOfRange(at * 2, (at + frames) * 2), frames)
        dropped += skipper.skippedFrames
        at += frames
    }
    expect("skip silence, seconds kept of 3", kept / rate.toDouble(), 2.0..2.1)
    expect("skip silence, frames accounted for", (kept + dropped) / rate.toDouble(), 2.999..3.001)
    expect("soft clip of 2.0", softClip(2f).toDouble(), 0.95..1.0)

    val parsed = runCatching {
        ParametricEq.parse("Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70\nFilter 2: ON PK Fc 2500 Hz Gain -3.1 dB Q 1.20\nFilter 3: OFF PK Fc 9000 Hz Gain 2 dB Q 2")
    }
    expect("ParametricEQ filters read", parsed.getOrNull()?.bands?.size?.toDouble() ?: -1.0, 2.0..2.0)
    expect("ParametricEQ preamp", parsed.getOrNull()?.preamp ?: 0.0, -6.21..-6.19)
    return ok
}
