/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * What the player does to the sound on its way out, as one immutable value the audio thread
 * picks up between buffers.
 */
data class AudioEffects(
    /** The equalizer's bands, or null while it's off. */
    val eq: List<EqBand>? = null,
    /** dB, applied with the equalizer. */
    val preamp: Double = 0.0,
    val spatial: Boolean = false,
    val skipSilence: Boolean = false,
    /** The lyrics menu's karaoke vocal reduction, 0 (off) to 1. */
    val vocalReduction: Float = 0f,
) {
    val shapesSound: Boolean get() = eq != null || spatial || vocalReduction > 0f
}

/**
 * The effects for one stream of interleaved stereo floats: vocal reduction, equalizer, spatial
 * audio and the silence skipper. Not thread-safe; each audio session owns one.
 */
class EffectsChain(private val sampleRate: Int) {
    private var applied: AudioEffects? = null
    private var filters = emptyArray<Biquad>()
    private var preampGain = 1f
    private val spatial = SpatialWidener(sampleRate)
    private val skipper = SilenceSkipper(sampleRate)
    private val vocals = CenteredVocalReducer()

    /** Frames the last [process] dropped as silence. */
    val skippedFrames: Int get() = skipper.dropped

    fun configure(effects: AudioEffects) {
        if (effects === applied) return
        val previous = applied
        applied = effects
        val active = effects.eq.orEmpty().filter { it.enabled && it.frequency < sampleRate / 2.0 && (it.gain != 0.0 || it.type == EqFilter.LPQ || it.type == EqFilter.HPQ) }
        // Keep each filter's history when only its settings change, so dragging a band doesn't click.
        if (active.size != filters.size) filters = Array(active.size) { Biquad() }
        active.forEachIndexed { i, band -> filters[i].set(band, sampleRate) }
        preampGain = if (effects.eq != null) 10.0.pow(effects.preamp / 20.0).toFloat() else 1f
        if (previous?.spatial != effects.spatial) spatial.reset()
        if (previous?.skipSilence != effects.skipSilence) skipper.reset()
    }

    /**
     * Runs [frames] frames of [input] through the chain and returns how many came out, in
     * [output]: fewer than went in while silence is being skipped.
     */
    fun process(input: FloatArray, frames: Int): Int {
        val effects = applied ?: AudioEffects()
        var buffer = input
        var count = frames
        if (effects.vocalReduction > 0f) vocals.process(input, frames, sampleRate, effects.vocalReduction) else vocals.bypass()
        if (effects.skipSilence) {
            count = skipper.process(input, frames)
            buffer = skipper.output
        } else {
            skipper.dropped = 0
        }
        for (filter in filters) filter.process(buffer, count)
        if (preampGain != 1f) for (i in 0 until count * 2) buffer[i] *= preampGain
        if (effects.spatial) spatial.process(buffer, count)
        output = buffer
        return count
    }

    /** Where [process] left its frames. */
    var output: FloatArray = FloatArray(0)
        private set

    fun reset() {
        filters.forEach { it.reset() }
        spatial.reset()
        skipper.reset()
        vocals.reset()
    }
}

/** Softens peaks above 0.9 instead of clipping them, the phone's equalizer's last step. */
fun softClip(x: Float): Float = when {
    x > 0.9f -> 0.9f + 0.1f * tanh((x - 0.9f) / 0.1f)
    x < -0.9f -> -(0.9f + 0.1f * tanh((-x - 0.9f) / 0.1f))
    else -> x
}

/**
 * One filter from Robert Bristow-Johnson's Audio EQ Cookbook, over interleaved stereo. Runs in
 * doubles: a 31 Hz band at 48 kHz is too fine for floats.
 */
internal class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0
    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    fun set(band: EqBand, sampleRate: Int) {
        val w = 2.0 * PI * band.frequency / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val q = band.q.coerceIn(0.05, 20.0)
        val a = 10.0.pow(band.gain / 40.0)
        val a0: Double
        when (band.type) {
            EqFilter.PK -> {
                val alpha = sinW / (2 * q)
                b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
            }
            EqFilter.LSC, EqFilter.HSC -> {
                // Shelf slope 1, as the phone uses.
                val alpha = sinW / 2 * sqrt(2.0)
                val twoSqrtAAlpha = 2 * sqrt(a) * alpha
                if (band.type == EqFilter.LSC) {
                    b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                    b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
                    a1 = -2 * ((a - 1) + (a + 1) * cosW)
                    a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
                } else {
                    b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                    b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
                    a1 = 2 * ((a - 1) - (a + 1) * cosW)
                    a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
                }
            }
            EqFilter.LPQ -> {
                val alpha = sinW / (2 * q)
                b0 = (1 - cosW) / 2; b1 = 1 - cosW; b2 = (1 - cosW) / 2
                a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
            }
            EqFilter.HPQ -> {
                val alpha = sinW / (2 * q)
                b0 = (1 + cosW) / 2; b1 = -(1 + cosW); b2 = (1 + cosW) / 2
                a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
            }
        }
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
    }

    fun process(buffer: FloatArray, frames: Int) {
        var i = 0
        repeat(frames) {
            val l = buffer[i].toDouble()
            val yl = b0 * l + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
            x2L = x1L; x1L = l; y2L = y1L; y1L = yl
            buffer[i] = yl.toFloat()
            val r = buffer[i + 1].toDouble()
            val yr = b0 * r + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
            x2R = x1R; x1R = r; y2R = y1R; y1R = yr
            buffer[i + 1] = yr.toFloat()
            i += 2
        }
        // Denormals crawl on some CPUs; a silent tail would otherwise slow the thread down.
        if (kotlin.math.abs(y1L) < 1e-25) { y1L = 0.0; y2L = 0.0 }
        if (kotlin.math.abs(y1R) < 1e-25) { y1R = 0.0; y2R = 0.0 }
    }

    fun reset() {
        x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
        x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
    }
}

/**
 * The phone's spatial audio: the stereo image 25% wider, plus a little of each channel fed to
 * the other ear 0.35 ms late and dulled above 700 Hz, the way the far ear hears a speaker.
 * Turned down by the sum of both so even full-scale anti-phase stereo can't clip.
 */
internal class SpatialWidener(sampleRate: Int) {
    private val width = 1.25f
    private val crossfeed = 0.2f
    private val outputGain = 1f / (width + crossfeed)
    private val lowpass = (1.0 - exp(-2.0 * PI * 700.0 / sampleRate)).toFloat()
    private val delayL = FloatArray((sampleRate * 0.35f / 1000f).roundToInt().coerceAtLeast(1))
    private val delayR = FloatArray(delayL.size)
    private var index = 0
    private var lowL = 0f
    private var lowR = 0f

    fun process(buffer: FloatArray, frames: Int) {
        var i = 0
        repeat(frames) {
            val l = buffer[i]
            val r = buffer[i + 1]
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            lowL += lowpass * (delayR[index] - lowL)
            lowR += lowpass * (delayL[index] - lowR)
            delayL[index] = l
            delayR[index] = r
            if (++index == delayL.size) index = 0
            buffer[i] = (mid + side + lowL * crossfeed) * outputGain
            buffer[i + 1] = (mid - side + lowR * crossfeed) * outputGain
            i += 2
        }
    }

    fun reset() {
        delayL.fill(0f)
        delayR.fill(0f)
        index = 0
        lowL = 0f
        lowR = 0f
    }
}

/**
 * Skip silence as the phone's player does it: a stretch quieter than about -30 dB that lasts
 * over 150 ms is cut down to 20 ms either side, so the song's own short rests stay.
 */
internal class SilenceSkipper(sampleRate: Int) {
    private val threshold = 1024f / 32768f
    private val minimum = sampleRate * 150 / 1000
    private val padding = sampleRate * 20 / 1000

    /** Silent frames waiting to see whether the silence lasts. */
    private val held = FloatArray(minimum * 2)
    private var heldFrames = 0

    /** The last [padding] frames of a silence being skipped, played back when the music returns. */
    private val tail = FloatArray(padding * 2)
    private var tailFrames = 0
    private var tailIndex = 0
    private var skipping = false

    var output = FloatArray(0)
        private set
    var dropped = 0
        internal set

    fun process(input: FloatArray, frames: Int): Int {
        val needed = (frames + minimum + padding) * 2
        if (output.size < needed) output = FloatArray(needed)
        dropped = 0
        var out = 0
        var i = 0
        repeat(frames) {
            val l = input[i]
            val r = input[i + 1]
            i += 2
            val silent = kotlin.math.abs(l) < threshold && kotlin.math.abs(r) < threshold
            when {
                skipping && silent -> {
                    // Keep only the newest 20 ms, to lead back into the music.
                    if (tailFrames == padding) dropped++ else tailFrames++
                    tail[tailIndex * 2] = l
                    tail[tailIndex * 2 + 1] = r
                    tailIndex = (tailIndex + 1) % padding
                }
                skipping -> {
                    val start = if (tailFrames == padding) tailIndex else 0
                    for (k in 0 until tailFrames) {
                        val t = (start + k) % padding
                        output[out++] = tail[t * 2]
                        output[out++] = tail[t * 2 + 1]
                    }
                    tailFrames = 0
                    tailIndex = 0
                    skipping = false
                    output[out++] = l
                    output[out++] = r
                }
                silent -> {
                    held[heldFrames * 2] = l
                    held[heldFrames * 2 + 1] = r
                    heldFrames++
                    if (heldFrames == minimum) {
                        // Long enough to skip: play its first 20 ms and drop the rest.
                        System.arraycopy(held, 0, output, out, padding * 2)
                        out += padding * 2
                        dropped += minimum - padding
                        heldFrames = 0
                        skipping = true
                    }
                }
                else -> {
                    if (heldFrames > 0) {
                        System.arraycopy(held, 0, output, out, heldFrames * 2)
                        out += heldFrames * 2
                        heldFrames = 0
                    }
                    output[out++] = l
                    output[out++] = r
                }
            }
        }
        return out / 2
    }

    fun reset() {
        heldFrames = 0
        tailFrames = 0
        tailIndex = 0
        skipping = false
        dropped = 0
    }
}
