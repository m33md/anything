/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2010 Bill Cox, Sonic Library
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Changes for Convx (C) 2026, GPL-3.0: ported to Kotlin, float samples, and a speed and pitch
 * that can change between buffers.
 */

package com.convx.desktop.audio

import kotlin.math.abs
import kotlin.math.min

/**
 * ExoPlayer's Sonic, the time stretcher the phone's player uses for its Tempo and Pitch and for
 * AutoMix's tempo matching, so a song sped up here sounds as it does there: [speed] changes how
 * fast it plays with the pitch kept, [pitch] moves the pitch with the speed kept.
 *
 * Interleaved float frames go in with [queueInput] and come out of [readOutput].
 */
class Sonic(private val sampleRate: Int, private val channels: Int) {
    private val minPeriod = sampleRate / MaximumPitch
    private val maxPeriod = sampleRate / MinimumPitch
    private val maxRequired = 2 * maxPeriod
    private val downSampleBuffer = FloatArray(maxRequired)

    @Volatile var speed = 1f
    @Volatile var pitch = 1f

    private var inputBuffer = FloatArray(maxRequired * channels)
    private var inputFrames = 0
    private var outputBuffer = FloatArray(maxRequired * channels)
    private var outputFrames = 0
    private var pitchBuffer = FloatArray(maxRequired * channels)
    private var pitchFrames = 0
    private var oldRatePosition = 0
    private var newRatePosition = 0
    private var lastRate = 1f
    private var remainingToCopy = 0
    private var prevPeriod = 0
    private var prevMinDiff = 0f
    private var minDiff = 0f
    private var maxDiff = 0f

    /** True when it would pass the sound through untouched. */
    val isIdentity: Boolean get() = abs(speed - 1f) < 0.0001f && abs(pitch - 1f) < 0.0001f

    /** Frames taken in and not yet given back, the delay it adds. */
    val pendingInputFrames: Int get() = inputFrames + pitchFrames

    val availableFrames: Int get() = outputFrames

    fun queueInput(samples: FloatArray, frames: Int) {
        inputBuffer = ensure(inputBuffer, inputFrames, frames)
        System.arraycopy(samples, 0, inputBuffer, inputFrames * channels, frames * channels)
        inputFrames += frames
        process()
    }

    /** Copies up to [maxFrames] frames into [out], returning how many. */
    fun readOutput(out: FloatArray, maxFrames: Int): Int {
        val frames = min(maxFrames, outputFrames)
        System.arraycopy(outputBuffer, 0, out, 0, frames * channels)
        outputFrames -= frames
        System.arraycopy(outputBuffer, frames * channels, outputBuffer, 0, outputFrames * channels)
        return frames
    }

    fun flush() {
        inputFrames = 0
        outputFrames = 0
        pitchFrames = 0
        oldRatePosition = 0
        newRatePosition = 0
        remainingToCopy = 0
        prevPeriod = 0
        prevMinDiff = 0f
        minDiff = 0f
        maxDiff = 0f
    }

    private fun ensure(buffer: FloatArray, frames: Int, additional: Int): FloatArray {
        val capacity = buffer.size / channels
        if (frames + additional <= capacity) return buffer
        return buffer.copyOf((3 * capacity / 2 + additional) * channels)
    }

    private fun removeProcessedInput(position: Int) {
        val remaining = inputFrames - position
        System.arraycopy(inputBuffer, position * channels, inputBuffer, 0, remaining * channels)
        inputFrames = remaining
    }

    private fun copyToOutput(samples: FloatArray, position: Int, frames: Int) {
        outputBuffer = ensure(outputBuffer, outputFrames, frames)
        System.arraycopy(samples, position * channels, outputBuffer, outputFrames * channels, frames * channels)
        outputFrames += frames
    }

    private fun copyInputToOutput(position: Int): Int {
        val frames = min(maxRequired, remainingToCopy)
        copyToOutput(inputBuffer, position, frames)
        remainingToCopy -= frames
        return frames
    }

    private fun downSampleInput(samples: FloatArray, position: Int, skip: Int) {
        val frames = maxRequired / skip
        val perValue = channels * skip
        val start = position * channels
        for (i in 0 until frames) {
            var value = 0f
            for (j in 0 until perValue) value += samples[start + i * perValue + j]
            downSampleBuffer[i] = value / perValue
        }
    }

    private fun findPitchPeriodInRange(samples: FloatArray, position: Int, minP: Int, maxP: Int, stride: Int): Int {
        var bestPeriod = 0
        var worstPeriod = 255
        var minD = 1f
        var maxD = 0f
        val start = position * stride
        for (period in minP..maxP) {
            var diff = 0f
            for (i in 0 until period) diff += abs(samples[start + i] - samples[start + period + i])
            if (diff * bestPeriod < minD * period) {
                minD = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxD * period) {
                maxD = diff
                worstPeriod = period
            }
        }
        minDiff = if (bestPeriod > 0) minD / bestPeriod else 0f
        maxDiff = maxD / worstPeriod
        return bestPeriod.coerceAtLeast(minP)
    }

    private fun previousPeriodBetter(): Boolean {
        if (minDiff == 0f || prevPeriod == 0) return false
        if (maxDiff > minDiff * 3) return false
        if (minDiff * 2 <= prevMinDiff * 3) return false
        return true
    }

    private fun findPitchPeriod(samples: FloatArray, position: Int): Int {
        val skip = if (sampleRate > AmdfFrequency) sampleRate / AmdfFrequency else 1
        var period: Int
        if (channels == 1 && skip == 1) {
            period = findPitchPeriodInRange(samples, position, minPeriod, maxPeriod, channels)
        } else {
            downSampleInput(samples, position, skip)
            period = findPitchPeriodInRange(downSampleBuffer, 0, minPeriod / skip, maxPeriod / skip, 1)
            if (skip != 1) {
                period *= skip
                val minP = (period - skip * 4).coerceAtLeast(minPeriod)
                val maxP = (period + skip * 4).coerceAtMost(maxPeriod)
                period = if (channels == 1) {
                    findPitchPeriodInRange(samples, position, minP, maxP, channels)
                } else {
                    downSampleInput(samples, position, 1)
                    findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP, 1)
                }
            }
        }
        val result = if (previousPeriodBetter()) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return result
    }

    private fun moveNewSamplesToPitchBuffer(originalOutputFrames: Int) {
        val frames = outputFrames - originalOutputFrames
        pitchBuffer = ensure(pitchBuffer, pitchFrames, frames)
        System.arraycopy(outputBuffer, originalOutputFrames * channels, pitchBuffer, pitchFrames * channels, frames * channels)
        outputFrames = originalOutputFrames
        pitchFrames += frames
    }

    private fun removePitchFrames(frames: Int) {
        if (frames == 0) return
        System.arraycopy(pitchBuffer, frames * channels, pitchBuffer, 0, (pitchFrames - frames) * channels)
        pitchFrames -= frames
    }

    private fun interpolate(input: FloatArray, at: Int, oldRate: Int, newRate: Int): Float {
        val left = input[at]
        val right = input[at + channels]
        val position = newRatePosition.toLong() * oldRate
        val leftPosition = oldRatePosition.toLong() * newRate
        val rightPosition = (oldRatePosition + 1).toLong() * newRate
        val ratio = (rightPosition - position).toFloat()
        val width = (rightPosition - leftPosition).toFloat()
        return (ratio * left + (width - ratio) * right) / width
    }

    private fun adjustRate(rate: Float, originalOutputFrames: Int) {
        if (outputFrames == originalOutputFrames) return
        var newRate = (sampleRate / rate).toInt()
        var oldRate = sampleRate
        while (newRate > (1 shl 14) || oldRate > (1 shl 14)) {
            newRate /= 2
            oldRate /= 2
        }
        moveNewSamplesToPitchBuffer(originalOutputFrames)
        for (position in 0 until pitchFrames - 1) {
            while ((oldRatePosition + 1).toLong() * newRate > newRatePosition.toLong() * oldRate) {
                outputBuffer = ensure(outputBuffer, outputFrames, 1)
                for (i in 0 until channels) {
                    outputBuffer[outputFrames * channels + i] = interpolate(pitchBuffer, position * channels + i, oldRate, newRate)
                }
                newRatePosition++
                outputFrames++
            }
            oldRatePosition++
            if (oldRatePosition == oldRate) {
                oldRatePosition = 0
                newRatePosition = 0
            }
        }
        removePitchFrames(pitchFrames - 1)
    }

    private fun skipPitchPeriod(samples: FloatArray, position: Int, speed: Float, period: Int): Int {
        val newFrames: Int
        if (speed >= 2f) {
            newFrames = (period / (speed - 1f)).toInt()
        } else {
            newFrames = period
            remainingToCopy = (period * (2f - speed) / (speed - 1f)).toInt()
        }
        outputBuffer = ensure(outputBuffer, outputFrames, newFrames)
        overlapAdd(newFrames, outputBuffer, outputFrames, samples, position, samples, position + period)
        outputFrames += newFrames
        return newFrames
    }

    private fun insertPitchPeriod(samples: FloatArray, position: Int, speed: Float, period: Int): Int {
        val newFrames: Int
        if (speed < 0.5f) {
            newFrames = (period * speed / (1f - speed)).toInt()
        } else {
            newFrames = period
            remainingToCopy = (period * (2f * speed - 1f) / (1f - speed)).toInt()
        }
        outputBuffer = ensure(outputBuffer, outputFrames, period + newFrames)
        System.arraycopy(samples, position * channels, outputBuffer, outputFrames * channels, period * channels)
        overlapAdd(newFrames, outputBuffer, outputFrames + period, samples, position + period, samples, position)
        outputFrames += period + newFrames
        return newFrames
    }

    private fun changeSpeed(speed: Float) {
        if (inputFrames < maxRequired) return
        val frames = inputFrames
        var position = 0
        do {
            if (remainingToCopy > 0) {
                position += copyInputToOutput(position)
            } else {
                val period = findPitchPeriod(inputBuffer, position)
                position += if (speed > 1f) {
                    period + skipPitchPeriod(inputBuffer, position, speed, period)
                } else {
                    insertPitchPeriod(inputBuffer, position, speed, period)
                }
            }
        } while (position + maxRequired <= frames)
        removeProcessedInput(position)
    }

    private fun process() {
        val p = pitch.coerceIn(0.25f, 4f)
        val s = speed.coerceIn(0.1f, 8f) / p
        val r = p
        if (r != lastRate) {
            // A new pitch restarts the resampler's phase; the frames waiting in it play on.
            oldRatePosition = 0
            newRatePosition = 0
            lastRate = r
        }
        if (r == 1f && pitchFrames > 0) {
            // Back to the natural pitch: what the resampler held goes out first, as it is.
            copyToOutput(pitchBuffer, 0, pitchFrames)
            pitchFrames = 0
        }
        val originalOutputFrames = outputFrames
        if (s > 1.00001f || s < 0.99999f) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, inputFrames)
            inputFrames = 0
        }
        if (r != 1f) adjustRate(r, originalOutputFrames)
    }

    private fun overlapAdd(
        frames: Int,
        out: FloatArray,
        outPosition: Int,
        rampDown: FloatArray,
        rampDownPosition: Int,
        rampUp: FloatArray,
        rampUpPosition: Int,
    ) {
        if (frames <= 0) return
        for (c in 0 until channels) {
            var o = outPosition * channels + c
            var u = rampUpPosition * channels + c
            var d = rampDownPosition * channels + c
            for (t in 0 until frames) {
                out[o] = (rampDown[d] * (frames - t) + rampUp[u] * t) / frames
                o += channels
                d += channels
                u += channels
            }
        }
    }

    private companion object {
        const val MinimumPitch = 65
        const val MaximumPitch = 400
        const val AmdfFrequency = 4000
    }
}
