/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The phone's reverb programs for creative transitions; Automatic changes it each time. */
@Serializable
enum class ReverbType(val label: String) { AUTO("Automatic"), ROOM("Room"), PLATE("Plate"), HALL("Hall") }

/** The phone's delay programs for creative transitions; Automatic changes it each time. */
@Serializable
enum class DelayType(val label: String) {
    AUTO("Automatic"),
    STEREO("Stereo"),
    PING_PONG("Ping-pong"),
    TAPE("Tape"),
    FILTERED("Filtered echo"),
    SLAPBACK("Slapback"),
}

/**
 * The phone's creative transition effects for one playing song, in its order: tape stop, then a
 * tempo-synced delay, then a reverb. Made only for songs AutoMix touches; off, each passes the
 * sound through untouched. Works in place on interleaved stereo floats.
 */
class DjEffects(sampleRate: Int) {
    val tail = DjTail(sampleRate)
    val delay = DjDelay(sampleRate)
    val ambience = DjAmbience(sampleRate)

    /** Echoes or a reverb are still sounding, so the end of the song waits for them to die away. */
    val isRinging: Boolean get() = delay.isRinging || ambience.isRinging

    fun process(samples: FloatArray, frames: Int) {
        tail.process(samples, frames)
        delay.process(samples, frames)
        ambience.process(samples, frames)
    }

    /** After a seek: what was ringing belonged to the sound before it. */
    fun reset() {
        tail.reset()
        delay.clearTail()
        ambience.stop()
    }

    /** The phone's stopTailEffects for one song. */
    fun stop() {
        tail.stop()
        ambience.stop()
        delay.frozen = false
        delay.wetMix = 0f
        delay.dryLevel = 1f
        delay.clearTail()
    }

    companion object {
        /** How long the end of a song keeps feeding silence to its echoes and reverb. */
        const val TailSeconds = 8
    }
}

/** The phone's DjTailAudioProcessor: a tape stop that keeps the frame count. */
class DjTail(private val sampleRate: Int) {
    @Volatile private var running = false
    @Volatile private var requestedFrames = 0L

    private val ringFrames = sampleRate * RingSeconds
    private val ring = FloatArray(ringFrames * 2)
    private var writeFrame = 0
    private var readPosition = 0.0
    private var tapeRate = 1.0
    private var tapeDecayPerFrame = 0.0

    /** Brakes the song to a standstill over [durationMs], as a turntable switched off. */
    fun startTapeStop(durationMs: Long) {
        requestedFrames = (durationMs * sampleRate / 1000L).coerceAtLeast(1L)
    }

    fun stop() {
        requestedFrames = 0L
        running = false
    }

    fun reset() {
        stop()
        writeFrame = 0
        ring.fill(0f)
    }

    fun process(samples: FloatArray, frames: Int) {
        val requested = requestedFrames
        if (requested > 0L) {
            requestedFrames = 0L
            tapeRate = 1.0
            tapeDecayPerFrame = 1.0 / requested
            readPosition = writeFrame.toDouble()
            running = true
        }
        if (!running) return
        var i = 0
        repeat(frames) {
            ring[writeFrame * 2] = samples[i]
            ring[writeFrame * 2 + 1] = samples[i + 1]
            writeFrame = (writeFrame + 1) % ringFrames
            if (tapeRate <= 0.0) {
                samples[i] = 0f
                samples[i + 1] = 0f
            } else {
                val base = floor(readPosition).toInt()
                val fraction = (readPosition - base).toFloat()
                val frameA = Math.floorMod(base, ringFrames)
                val frameB = (frameA + 1) % ringFrames
                for (channel in 0..1) {
                    val a = ring[frameA * 2 + channel]
                    val b = ring[frameB * 2 + channel]
                    samples[i + channel] = a + (b - a) * fraction
                }
                readPosition += tapeRate
                tapeRate = (tapeRate - tapeDecayPerFrame).coerceAtLeast(0.0)
            }
            i += 2
        }
    }

    private companion object {
        const val RingSeconds = 4
    }
}

/** The phone's DelayAudioProcessor: a bounded stereo delay with program feedback and ducking. */
class DjDelay(private val sampleRate: Int, initialDelayMs: Long = 220L, private val feedback: Float = 0.78f) {
    @Volatile private var delayMs = initialDelayMs.coerceIn(1L, MaxDelayMs)
    @Volatile var delayType: DelayType = DelayType.AUTO
    @Volatile var wetMix: Float = 0f
    @Volatile var frozen: Boolean = false
    @Volatile var freezeFeedback: Float = 0.72f

    /** Fades the song into the delay line while leaving what is already ringing to decay. */
    @Volatile var dryLevel: Float = 1f

    private val ringFrames = (sampleRate * MaxDelayMs / 1000L).toInt().coerceAtLeast(2)
    private val ring = FloatArray(ringFrames * 2)
    private var writeFrame = 0
    private var delayFrames = 1
    private var previousDelayFrames = 1
    private var retuneRemaining = 0
    private val retuneFrames = (sampleRate * RetuneSeconds).toInt().coerceAtLeast(1)
    private var active = false
    private var configuredType = DelayType.AUTO
    private var lowpassCoefficient = 0f
    private var highpassCoefficient = 0f
    private val envelopeAttack = (1.0 - exp(-1.0 / (sampleRate * 0.012))).toFloat()
    private val envelopeRelease = (1.0 - exp(-1.0 / (sampleRate * 0.18))).toFloat()
    private var envelope = 0f
    private val lowpassState = FloatArray(2)
    private val highpassInput = FloatArray(2)
    private val highpassState = FloatArray(2)
    private val tailRevision = AtomicLong()
    private var appliedTailRevision = 0L

    init {
        configureProgram(resolvedType())
        resetAudioState()
    }

    val isRinging: Boolean get() = active && (wetMix > 0f || frozen)

    fun setDelayMs(ms: Long) {
        delayMs = ms.coerceIn(1L, MaxDelayMs)
    }

    fun clearTail() {
        tailRevision.incrementAndGet()
    }

    fun process(samples: FloatArray, frames: Int) {
        val revision = tailRevision.get()
        if (appliedTailRevision != revision) {
            resetAudioState()
            appliedTailRevision = revision
        }
        val mix = wetMix.takeIf(Float::isFinite)?.coerceIn(0f, MaxWetMix) ?: 0f
        val freeze = frozen
        if (mix == 0f && !freeze) {
            if (active) resetAudioState()
            return
        }
        val program = resolvedType()
        if (configuredType != program) configureProgram(program)
        updateDelay(program)
        active = true
        val normalFeedback = if (program == DelayType.SLAPBACK) 0f else feedback.takeIf(Float::isFinite)?.coerceIn(0f, MaxFeedback) ?: 0f
        val tailFeedback = freezeFeedback.takeIf(Float::isFinite)?.coerceIn(0f, MaxFeedback) ?: 0f
        val dryGain = 1f - mix * 0.25f
        val level = dryLevel.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f

        var i = 0
        repeat(frames) {
            val left = samples[i] * level
            val right = samples[i + 1] * level
            val peak = max(abs(left), abs(right))
            envelope += (if (peak > envelope) envelopeAttack else envelopeRelease) * (peak - envelope)
            val duck = 1f / (1f + 1.8f * envelope)
            val readFrame = wrappedFrame(writeFrame - delayFrames)
            val previousReadFrame = wrappedFrame(writeFrame - previousDelayFrames)
            val tapBlend = if (retuneRemaining > 0) 1f - retuneRemaining.toFloat() / retuneFrames else 1f

            var wetLeft = 0f
            var wetRight = 0f
            for (channel in 0..1) {
                val sourceChannel = if (program == DelayType.PING_PONG) 1 - channel else channel
                val delayed = ring[previousReadFrame * 2 + sourceChannel] * (1f - tapBlend) +
                    ring[readFrame * 2 + sourceChannel] * tapBlend
                val filtered = filterFeedback(delayed, channel, program)
                val gain = if (freeze) tailFeedback else normalFeedback
                val dry = if (channel == 0) left else right
                var fed = (if (freeze) 0f else dry) + filtered * gain
                if (program == DelayType.TAPE) fed = tapeSaturate(fed)
                ring[writeFrame * 2 + channel] = fed.coerceIn(-1.5f, 1.5f)
                val wet = if (freeze) filtered * tailFeedback else filtered * mix * duck
                if (channel == 0) wetLeft = wet else wetRight = wet
            }
            val wetMid = (wetLeft + wetRight) * 0.5f
            val wetSide = (wetLeft - wetRight) * 0.5f * WetWidth
            samples[i] = limit((if (freeze) 0f else left * dryGain) + wetMid + wetSide)
            samples[i + 1] = limit((if (freeze) 0f else right * dryGain) + wetMid - wetSide)
            if (++writeFrame == ringFrames) writeFrame = 0
            if (retuneRemaining > 0) retuneRemaining--
            i += 2
        }
    }

    private fun resolvedType(): DelayType = delayType.let { if (it == DelayType.AUTO) DelayType.STEREO else it }

    private fun configureProgram(program: DelayType) {
        configuredType = program
        val lowpassHz = when (program) {
            DelayType.TAPE -> 3500.0
            DelayType.FILTERED -> 2400.0
            else -> 8500.0
        }.coerceAtMost(sampleRate * 0.4)
        lowpassCoefficient = (1.0 - exp(-2.0 * PI * lowpassHz / sampleRate)).toFloat()
        highpassCoefficient = exp(-2.0 * PI * 180.0 / sampleRate).toFloat()
        lowpassState.fill(0f)
        highpassInput.fill(0f)
        highpassState.fill(0f)
    }

    private fun updateDelay(program: DelayType) {
        val ms = if (program == DelayType.SLAPBACK) delayMs.coerceIn(80L, 160L) else delayMs
        val target = (sampleRate * ms / 1000L).toInt().coerceIn(1, ringFrames - 1)
        if (target == delayFrames) return
        previousDelayFrames = delayFrames
        delayFrames = target
        retuneRemaining = if (active) retuneFrames else 0
    }

    private fun filterFeedback(value: Float, channel: Int, program: DelayType): Float {
        lowpassState[channel] += lowpassCoefficient * (value - lowpassState[channel])
        val lowpass = lowpassState[channel]
        if (program != DelayType.FILTERED) return lowpass
        val highpass = highpassCoefficient * (highpassState[channel] + lowpass - highpassInput[channel])
        highpassInput[channel] = lowpass
        highpassState[channel] = highpass
        return highpass
    }

    private fun wrappedFrame(frame: Int): Int = if (frame < 0) frame + ringFrames else frame

    private fun tapeSaturate(value: Float): Float {
        val limited = value.coerceIn(-3f, 3f)
        val squared = limited * limited
        return limited * (27f + squared) / (27f + 9f * squared)
    }

    /** The phone's soft knee above 0.9 of full scale. */
    private fun limit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= 0.9f) return value
        val excess = (magnitude - 0.9f) / 0.1f
        val limited = 0.9f + 0.1f * excess / (1f + excess)
        return if (value < 0f) -limited else limited
    }

    private fun resetAudioState() {
        ring.fill(0f)
        lowpassState.fill(0f)
        highpassInput.fill(0f)
        highpassState.fill(0f)
        envelope = 0f
        writeFrame = 0
        active = false
        retuneRemaining = 0
        val ms = if (resolvedType() == DelayType.SLAPBACK) delayMs.coerceIn(80L, 160L) else delayMs
        delayFrames = (sampleRate * ms / 1000L).toInt().coerceIn(1, ringFrames - 1)
        previousDelayFrames = delayFrames
    }

    private companion object {
        const val MaxDelayMs = 2000L
        const val MaxFeedback = 0.92f
        const val MaxWetMix = 0.7f
        const val RetuneSeconds = 0.02f
        const val WetWidth = 1.4f
    }
}

/** The phone's DjAmbienceAudioProcessor: a transition reverb whose tails come out wide. */
class DjAmbience(private val sampleRate: Int) {
    @Volatile var wetMix = 0f
    @Volatile var reverbType = ReverbType.ROOM
    @Volatile private var clearRequested = false

    private val scale = sampleRate / ReferenceSampleRate
    private val stereoSpreadFrames = (37 * scale).roundToInt().coerceAtLeast(1)
    private val frameDry = FloatArray(2)
    private val frameWet = FloatArray(2)
    private val combs = Array(2) { Array(CombCount) { FloatArray((MaxCombFrames * scale).roundToInt() + stereoSpreadFrames + 1) } }
    private val diffusion = Array(2) { Array(DiffusionCount) { FloatArray((MaxDiffusionFrames * scale).roundToInt() + stereoSpreadFrames + 1) } }
    private val early = Array(2) { FloatArray((sampleRate * MaxEarlySeconds).roundToInt().coerceAtLeast(1)) }
    private val combPositions = Array(2) { IntArray(CombCount) }
    private val diffusionPositions = Array(2) { IntArray(DiffusionCount) }
    private val combDamping = Array(2) { FloatArray(CombCount) }
    private val highPassState = FloatArray(2)
    private val combLengths = IntArray(CombCount)
    private val diffusionLengths = IntArray(DiffusionCount)
    private val feedback = FloatArray(CombCount)
    private val earlyOffsets = IntArray(EarlyTapCount)
    private val mixStep = smoothing(MixGlideSeconds)
    private val highPassStep = cutoffStep(140f)
    private val attack = smoothing(0.012)
    private val release = smoothing(0.24)
    private var currentMix = 0f
    private var damping = 0f
    private var envelope = 0f
    private var appliedType: ReverbType? = null
    private var program = ReverbProgram.forType(ReverbType.ROOM)
    private var earlyPosition = 0
    private var preDelayFrames = 0
    private var hasTail = false

    init {
        applyProgram()
    }

    val isRinging: Boolean get() = hasTail && wetMix > 0f

    fun stop() {
        wetMix = 0f
        clearRequested = true
    }

    fun process(samples: FloatArray, frames: Int) {
        if (clearRequested) {
            clearRequested = false
            clearTail()
            currentMix = 0f
        }
        val targetMix = wetMix.coerceIn(0f, MaxWetMix)
        if (targetMix == 0f && currentMix < SettledMix) {
            if (hasTail) clearTail()
            currentMix = 0f
            return
        }
        if (appliedType != reverbType) applyProgram()
        hasTail = true
        var i = 0
        repeat(frames) {
            currentMix += (targetMix - currentMix) * mixStep
            if (abs(currentMix - targetMix) < SettledMix) currentMix = targetMix
            frameDry[0] = samples[i]
            frameDry[1] = samples[i + 1]
            val mid = (frameDry[0] + frameDry[1]) * 0.5f
            var peak = 0f
            for (channel in 0..1) {
                peak = maxOf(peak, abs(frameDry[channel]))
                // Both sides feed one tank, so the tails come out wide and decorrelated instead of
                // two separate mono reverbs.
                frameWet[channel] = reverb(channel, frameDry[channel] + (mid - frameDry[channel]) * CrossFeed)
            }
            val wetMid = (frameWet[0] + frameWet[1]) * 0.5f
            val wetSide = (frameWet[0] - frameWet[1]) * 0.5f * WetWidth
            frameWet[0] = wetMid + wetSide
            frameWet[1] = wetMid - wetSide
            val duckedMix = currentMix / (1f + envelope * DuckAmount)
            for (channel in 0..1) {
                val limitedWet = frameWet[channel] / (1f + abs(frameWet[channel]) / WetKnee)
                samples[i + channel] = frameDry[channel] * (1f - duckedMix) + limitedWet * duckedMix
            }
            envelope += (peak - envelope) * if (peak > envelope) attack else release
            earlyPosition = if (earlyPosition + 1 == early[0].size) 0 else earlyPosition + 1
            i += 2
        }
    }

    private fun reverb(channel: Int, input: Float): Float {
        highPassState[channel] += (input - highPassState[channel]) * highPassStep
        val earlyLine = early[channel]
        earlyLine[earlyPosition] = input - highPassState[channel]
        var earlySignal = 0f
        for (offset in earlyOffsets) {
            var index = earlyPosition - offset
            if (index < 0) index += earlyLine.size
            earlySignal += earlyLine[index]
        }
        earlySignal /= EarlyTapCount
        var preDelayIndex = earlyPosition - preDelayFrames
        if (preDelayIndex < 0) preDelayIndex += earlyLine.size
        var diffused = earlyLine[preDelayIndex]
        for (index in program.inputDiffusionFrames.indices) diffused = diffuse(channel, index, diffused)

        var wet = 0f
        val channelCombs = combs[channel]
        val positions = combPositions[channel]
        val damped = combDamping[channel]
        for (index in channelCombs.indices) {
            val position = positions[index]
            val line = channelCombs[index]
            val delayed = line[position]
            damped[index] += (delayed - damped[index]) * damping
            line[position] = diffused * (1f - feedback[index]) + damped[index] * feedback[index]
            val length = combLengths[index] + channel * stereoSpreadFrames
            positions[index] = if (position + 1 == length) 0 else position + 1
            wet += delayed
        }
        wet /= CombCount
        for (index in program.outputDiffusionFrames.indices) wet = diffuse(channel, InputDiffusionCount + index, wet)
        return earlySignal * program.earlyMix + wet * (1f - program.earlyMix) * program.lateGain
    }

    private fun diffuse(channel: Int, index: Int, input: Float): Float {
        val line = diffusion[channel][index]
        val position = diffusionPositions[channel][index]
        val delayed = line[position]
        val output = delayed - program.diffusion * input
        line[position] = input + program.diffusion * output
        val length = diffusionLengths[index] + channel * stereoSpreadFrames
        diffusionPositions[channel][index] = if (position + 1 == length) 0 else position + 1
        return output
    }

    private fun applyProgram() {
        clearTail()
        val type = reverbType
        appliedType = type
        program = ReverbProgram.forType(type)
        for (index in combLengths.indices) {
            combLengths[index] = (program.combFrames[index] * scale).roundToInt().coerceAtLeast(1)
            feedback[index] = exp(-6.907755 * combLengths[index] / sampleRate / program.decaySeconds)
                .toFloat().coerceAtMost(0.985f)
        }
        for (index in program.inputDiffusionFrames.indices) {
            diffusionLengths[index] = (program.inputDiffusionFrames[index] * scale).roundToInt().coerceAtLeast(1)
        }
        for (index in program.outputDiffusionFrames.indices) {
            diffusionLengths[InputDiffusionCount + index] = (program.outputDiffusionFrames[index] * scale).roundToInt().coerceAtLeast(1)
        }
        preDelayFrames = sampleRate * program.preDelayMs / 1000
        for (index in earlyOffsets.indices) earlyOffsets[index] = sampleRate * (program.preDelayMs + program.earlyTapsMs[index]) / 1000
        damping = cutoffStep(program.highCutHz)
    }

    private fun cutoffStep(hz: Float) = (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat()

    private fun smoothing(seconds: Double) = (1.0 - exp(-1.0 / (sampleRate * seconds))).toFloat()

    private fun clearTail() {
        for (channel in 0..1) {
            for (line in combs[channel]) line.fill(0f)
            for (line in diffusion[channel]) line.fill(0f)
            early[channel].fill(0f)
            combPositions[channel].fill(0)
            diffusionPositions[channel].fill(0)
            combDamping[channel].fill(0f)
        }
        highPassState.fill(0f)
        earlyPosition = 0
        envelope = 0f
        hasTail = false
    }

    private companion object {
        const val ReferenceSampleRate = 44_100f
        const val CombCount = 4
        const val InputDiffusionCount = 2
        const val DiffusionCount = 5
        const val EarlyTapCount = 4
        const val MaxCombFrames = 2_953
        const val MaxDiffusionFrames = 809
        const val MaxEarlySeconds = 0.1f
        const val MixGlideSeconds = 0.045
        const val MaxWetMix = 0.55f
        const val SettledMix = 0.0001f
        const val DuckAmount = 1.5f
        const val CrossFeed = 0.6f
        const val WetWidth = 1.5f

        /** The phone's 49152 in 16-bit steps: where the wet's soft limit bends. */
        const val WetKnee = 1.5f
    }
}

/** The phone's ReverbProgram: Room, Plate and Hall. */
internal class ReverbProgram(
    val preDelayMs: Int,
    val decaySeconds: Float,
    val highCutHz: Float,
    val diffusion: Float,
    val earlyMix: Float,
    val lateGain: Float,
    val combFrames: IntArray,
    val inputDiffusionFrames: IntArray,
    val outputDiffusionFrames: IntArray,
    val earlyTapsMs: IntArray,
) {
    companion object {
        fun forType(type: ReverbType): ReverbProgram = when (type) {
            ReverbType.AUTO, ReverbType.ROOM -> Room
            ReverbType.PLATE -> Plate
            ReverbType.HALL -> Hall
        }

        private val Room = ReverbProgram(
            preDelayMs = 8,
            decaySeconds = 4f,
            highCutHz = 5_200f,
            diffusion = 0.35f,
            earlyMix = 0.38f,
            lateGain = 0.8f,
            combFrames = intArrayOf(743, 1111, 1297, 1531),
            inputDiffusionFrames = intArrayOf(),
            outputDiffusionFrames = intArrayOf(151, 337),
            earlyTapsMs = intArrayOf(3, 7, 11, 17),
        )
        private val Plate = ReverbProgram(
            preDelayMs = 20,
            decaySeconds = 6f,
            highCutHz = 7_000f,
            diffusion = 0.6f,
            earlyMix = 0.08f,
            lateGain = 0.85f,
            combFrames = intArrayOf(1557, 1617, 1491, 1422),
            inputDiffusionFrames = intArrayOf(142, 379),
            outputDiffusionFrames = intArrayOf(225, 556, 441),
            earlyTapsMs = intArrayOf(5, 9, 13, 19),
        )
        private val Hall = ReverbProgram(
            preDelayMs = 34,
            decaySeconds = 8f,
            highCutHz = 3_900f,
            diffusion = 0.52f,
            earlyMix = 0.2f,
            lateGain = 0.75f,
            combFrames = intArrayOf(1913, 2237, 2473, 2953),
            inputDiffusionFrames = intArrayOf(279),
            outputDiffusionFrames = intArrayOf(443, 613, 809),
            earlyTapsMs = intArrayOf(12, 24, 41, 59),
        )
    }
}
