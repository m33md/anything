/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The phone's DjFilterAudioProcessor for one playing song: a low-pass and a high-pass, each two
 * Butterworth stages, whose cutoffs glide to where AutoMix asks rather than jumping, plus the
 * stereo narrowing and panning the creative transitions use. Open, it leaves the sound alone.
 * Works in place on interleaved stereo floats; the targets are set from any thread.
 */
class DjFilter(private val sampleRate: Int) {
    @Volatile var lowPassHz: Float = OpenLowPassHz
    @Volatile var highPassHz: Float = OpenHighPassHz
    @Volatile var stereoWidth: Float = 1f

    /** -1 hard left to 1 hard right. */
    @Volatile var pan: Float = 0f

    @Volatile private var snapRequested = false

    private var currentLowPass = OpenLowPassHz
    private var currentHighPass = OpenHighPassHz
    private var currentWidth = 1f
    private var currentPan = 0f
    private var appliedLowPass = Float.NaN
    private var appliedHighPass = Float.NaN
    private val lowPass = Array(2) { arrayOf(Biquad(), Biquad()) }
    private val highPass = Array(2) { arrayOf(Biquad(), Biquad()) }

    /** The next buffer starts at the targets instead of gliding there. */
    fun snapToTargets() {
        snapRequested = true
    }

    fun open() {
        lowPassHz = OpenLowPassHz
        highPassHz = OpenHighPassHz
        stereoWidth = 1f
        pan = 0f
        snapToTargets()
    }

    /** After a seek: the filters' memory belongs to the sound before it. */
    fun reset() {
        lowPass.forEach { stages -> stages.forEach { it.reset() } }
        highPass.forEach { stages -> stages.forEach { it.reset() } }
        currentLowPass = lowPassHz
        currentHighPass = highPassHz
        currentWidth = stereoWidth.coerceIn(0f, 1f)
        currentPan = pan
    }

    fun process(samples: FloatArray, frames: Int) {
        if (snapRequested) {
            snapRequested = false
            currentLowPass = lowPassHz
            currentHighPass = highPassHz
            currentWidth = stereoWidth.coerceIn(0f, 1f)
            currentPan = pan
        }
        if (isOpen()) return
        var done = 0
        while (done < frames) {
            val chunk = minOf(ChunkFrames, frames - done)
            advance(chunk)
            val lowOn = currentLowPass < OpenLowPassHz
            val highOn = currentHighPass > OpenHighPassHz
            if (!nearlyEqual(currentLowPass, appliedLowPass)) {
                lowPass[0][0].setLowPass(sampleRate, currentLowPass)
                for (channel in lowPass) for (stage in channel) if (stage !== lowPass[0][0]) stage.copyFrom(lowPass[0][0])
                appliedLowPass = currentLowPass
            }
            if (!nearlyEqual(currentHighPass, appliedHighPass)) {
                highPass[0][0].setHighPass(sampleRate, currentHighPass)
                for (channel in highPass) for (stage in channel) if (stage !== highPass[0][0]) stage.copyFrom(highPass[0][0])
                appliedHighPass = currentHighPass
            }
            val shaping = currentWidth < 0.999f || abs(currentPan) > PanEpsilon
            val toLeft = (-currentPan).coerceAtLeast(0f)
            val toRight = currentPan.coerceAtLeast(0f)
            var i = done * 2
            repeat(chunk) {
                val left = filter(samples[i].toDouble(), 0, lowOn, highOn)
                val right = filter(samples[i + 1].toDouble(), 1, lowOn, highOn)
                if (shaping) {
                    val mid = (left + right) * 0.5
                    val side = (left - right) * 0.5 * currentWidth
                    val wideLeft = mid + side
                    val wideRight = mid - side
                    // The far side folds into the near one, so panning moves the sound instead of dropping half of it.
                    samples[i] = (wideLeft * (1.0 - toRight) + wideRight * toLeft * FoldGain).toFloat()
                    samples[i + 1] = (wideRight * (1.0 - toLeft) + wideLeft * toRight * FoldGain).toFloat()
                } else {
                    samples[i] = left.toFloat()
                    samples[i + 1] = right.toFloat()
                }
                i += 2
            }
            done += chunk
        }
    }

    private fun filter(input: Double, channel: Int, lowOn: Boolean, highOn: Boolean): Double {
        var sample = input
        if (lowOn) for (stage in lowPass[channel]) sample = stage.process(sample)
        if (highOn) for (stage in highPass[channel]) sample = stage.process(sample)
        return sample
    }

    private fun advance(frames: Int) {
        val targetLow = lowPassHz
        val targetHigh = highPassHz
        val targetWidth = stereoWidth.coerceIn(0f, 1f)
        currentPan += ((pan - currentPan) * (1.0 - exp(-frames.toDouble() / (sampleRate * PanTimeConstant)))).toFloat()
        if (abs(currentPan - pan) < PanEpsilon) currentPan = pan
        if (currentLowPass == targetLow && currentHighPass == targetHigh && currentWidth == targetWidth) return
        val alpha = 1.0 - exp(-frames.toDouble() / (sampleRate * GlideTimeConstant))
        currentLowPass = glide(currentLowPass, targetLow, alpha)
        currentHighPass = glide(currentHighPass, targetHigh, alpha)
        currentWidth += ((targetWidth - currentWidth) * alpha).toFloat()
        if (abs(currentWidth - targetWidth) < 0.001f) currentWidth = targetWidth
    }

    private fun glide(current: Float, target: Float, alpha: Double): Float {
        if (current == target) return target
        val next = exp(ln(current.toDouble()) + (ln(target.toDouble()) - ln(current.toDouble())) * alpha)
        return if (abs(next - target) < target * 0.001) target else next.toFloat()
    }

    private fun nearlyEqual(a: Float, b: Float): Boolean = abs(a - b) < b * 0.001f

    private fun isOpen(): Boolean =
        currentLowPass >= OpenLowPassHz && currentHighPass <= OpenHighPassHz &&
            lowPassHz >= OpenLowPassHz && highPassHz <= OpenHighPassHz &&
            currentWidth >= 0.999f && stereoWidth >= 0.999f &&
            abs(currentPan) <= PanEpsilon && abs(pan) <= PanEpsilon

    companion object {
        const val OpenLowPassHz = 20_000f
        const val OpenHighPassHz = 20f
        private const val ChunkFrames = 128
        private const val GlideTimeConstant = 0.35
        private const val PanTimeConstant = 0.03
        private const val PanEpsilon = 0.002f
        private const val FoldGain = 0.7071
    }
}

/** A second-order section, as the phone's dj/Biquad. */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setLowPass(sampleRate: Int, cutoffHz: Float, q: Double = ButterworthQ) = configure(sampleRate, cutoffHz, q, highPass = false)

    fun setHighPass(sampleRate: Int, cutoffHz: Float, q: Double = ButterworthQ) = configure(sampleRate, cutoffHz, q, highPass = true)

    private fun configure(sampleRate: Int, cutoffHz: Float, q: Double, highPass: Boolean) {
        val clamped = cutoffHz.coerceIn(MinCutoffHz, sampleRate * 0.45f)
        val omega = 2.0 * PI * clamped / sampleRate
        val cosOmega = cos(omega)
        val alpha = sin(omega) / (2.0 * q)
        val a0 = 1.0 + alpha
        val numerator = if (highPass) 1.0 + cosOmega else 1.0 - cosOmega
        b0 = (numerator / 2.0) / a0
        b1 = (if (highPass) -numerator else numerator) / a0
        b2 = b0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun copyFrom(other: Biquad) {
        b0 = other.b0
        b1 = other.b1
        b2 = other.b2
        a1 = other.a1
        a2 = other.a2
    }

    fun process(input: Double): Double {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    private companion object {
        const val ButterworthQ = 0.707
        const val MinCutoffHz = 20f
    }
}
