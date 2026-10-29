/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), by way of the Convx phone app's
 * native analyzer (audio_analysis.cpp, tempo_analysis.cpp), line for line into Kotlin so the
 * numbers the transition policy was tuned against stay the same.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Convx contributors (adapted from an open-source base)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into the reference app -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.convx.desktop.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline whole-track analysis: envelope, transition, tempo, key, spectral and structure
 * features, all DSP, no model. Mono samples at [SampleRate] in, [Features] out. It allocates
 * and does O(n) work, so it runs on a worker thread.
 */
object AudioAnalysis {
    /** The rate the phone's analyzer reads at; the decoder resamples to it. */
    const val SampleRate = 11_025.0

    data class EnergyPoint(val time: Double, val energy: Double)

    /** What the transition policy reads of a track; see TrackFeatures on the phone. */
    data class Features(
        val duration: Double = 0.0,
        val bpm: Double = 0.0,
        val beatInterval: Double = 0.0,
        val firstBeat: Double = 0.0,
        val beatConfidence: Double = 0.0,
        val key: String = "",
        val keyConfidence: Double = 0.0,
        val audibleStartTime: Double = 0.0,
        val pickupTime: Double = 0.0,
        val introEndTime: Double = 0.0,
        val outroStartTime: Double = 0.0,
        val contentEndTime: Double = 0.0,
        val mixInTime: Double = 0.0,
        val mixOutTime: Double = 0.0,
        val vocalProbability: Double = 0.0,
        val beats: List<Double> = emptyList(),
        val downbeats: List<Double> = emptyList(),
        val phraseBoundaries: List<Double> = emptyList(),
        val vocalActivityMask: List<Double> = emptyList(),
        val energyCurve: List<EnergyPoint> = emptyList(),
        val lowEnergyCurve: List<EnergyPoint> = emptyList(),
        val mixInCandidates: List<MixCandidate> = emptyList(),
        val mixOutCandidates: List<MixCandidate> = emptyList(),
        val loudnessLufs: Double = -70.0,
    )

    class Tempo(
        val bpm: Double = 0.0,
        val beatInterval: Double = 0.0,
        val firstBeat: Double = 0.0,
        val confidence: Double = 0.0,
        val beats: List<Double> = emptyList(),
        val downbeats: List<Double> = emptyList(),
    )

    private class Envelope {
        val windowSeconds = 0.25
        var noiseFloor = 0.0
        var reference = 0.0
        var threshold = 0.0
        var audibleStart = 0.0
        var pickupConfidence = 0.0
        var contentEnd = 0.0
        val levels = ArrayList<Double>()
    }

    private class Phrase(val start: Double, val end: Double, val type: String, val confidence: Double)

    /** The working result, filled stage by stage as the C++ does. */
    private class Result {
        var duration = 0.0
        var bpm = 0.0
        var beatInterval = 0.0
        var firstBeat = 0.0
        var beatConfidence = 0.0
        var beats: List<Double> = emptyList()
        var downbeats: List<Double> = emptyList()
        val phraseBoundaries = ArrayList<Double>()
        val phrases = ArrayList<Phrase>()
        var key = ""
        var keyConfidence = 0.0
        var chroma = DoubleArray(12)
        var audibleStartTime = 0.0
        var pickupTime = 0.0
        var pickupConfidence = 0.0
        var mixInTime = 0.0
        var mixInConfidence = 0.0
        var introEndTime = 0.0
        var outroStartTime = 0.0
        var contentEndTime = 0.0
        var mixOutTime = 0.0
        var loudnessLufs = -70.0
        var peakDbfs = -70.0
        var dynamicRangeDb = 0.0
        val energyCurve = ArrayList<EnergyPoint>()
        val lowEnergyCurve = ArrayList<EnergyPoint>()
        val vocalActivityMask = ArrayList<Double>()
        var mixInCandidates: List<MixCandidate> = emptyList()
        var mixOutCandidates: List<MixCandidate> = emptyList()
        var vocalProbability = 0.0
    }

    private fun clamp(value: Double, minimum: Double, maximum: Double) = max(minimum, min(maximum, value))

    private fun toDb(value: Double) = if (value > 1e-9) 20.0 * log10(value) else -70.0

    private fun percentile(values: List<Double>, ratio: Double): Double {
        if (values.isEmpty()) return 0.0
        val index = (clamp(ratio, 0.0, 1.0) * (values.size - 1)).toInt()
        return values.sorted()[index]
    }

    private fun average(values: List<Double>, start: Int, end: Int): Double {
        val s = min(start, values.size)
        val e = min(max(s, end), values.size)
        if (s == e) return 0.0
        var sum = 0.0
        for (i in s until e) sum += values[i]
        return sum / (e - s)
    }

    private fun hasMaterialRecovery(levels: List<Double>, start: Int, sustain: Int, reference: Double, quietLevel: Double): Boolean {
        if (reference <= 0 || quietLevel >= reference * 0.38) return false
        val threshold = max(reference * 0.72, quietLevel * 1.8)
        var index = start
        while (index + sustain <= levels.size) {
            if (average(levels, index, index + sustain) >= threshold) return true
            index++
        }
        return false
    }

    private fun hasQuietThenRecovery(levels: List<Double>, start: Int, sustain: Int, reference: Double): Boolean {
        if (reference <= 0) return false
        var foundQuiet = false
        var index = start
        while (index + sustain <= levels.size) {
            val avg = average(levels, index, index + sustain)
            if (avg < reference * 0.38) foundQuiet = true
            else if (foundQuiet && avg >= reference * 0.72) return true
            index++
        }
        return false
    }

    /** Unnormalized in-place radix-2 FFT over a power-of-two frame. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val size = re.size
        var swapped = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while ((swapped and bit) != 0) {
                swapped = swapped xor bit
                bit = bit shr 1
            }
            swapped = swapped xor bit
            if (index < swapped) {
                var t = re[index]; re[index] = re[swapped]; re[swapped] = t
                t = im[index]; im[index] = im[swapped]; im[swapped] = t
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val rootRe = cos(angle)
            val rootIm = sin(angle)
            var start = 0
            while (start < size) {
                var wRe = 1.0
                var wIm = 0.0
                val half = length / 2
                for (offset in 0 until half) {
                    val a = start + offset
                    val b = a + half
                    val oddRe = re[b] * wRe - im[b] * wIm
                    val oddIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - oddRe
                    im[b] = im[a] - oddIm
                    re[a] += oddRe
                    im[a] += oddIm
                    val nRe = wRe * rootRe - wIm * rootIm
                    wIm = wRe * rootIm + wIm * rootRe
                    wRe = nRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun rmsWindows(samples: FloatArray, windowSize: Int): ArrayList<Double> {
        val levels = ArrayList<Double>()
        var start = 0
        while (start < samples.size) {
            val end = min(samples.size, start + windowSize)
            var sum = 0.0
            for (i in start until end) sum += samples[i].toDouble() * samples[i]
            levels += sqrt(sum / max(1, end - start))
            start += windowSize
        }
        return levels
    }

    private fun analyzeEnvelope(samples: FloatArray, sampleRate: Double, duration: Double): Envelope {
        val result = Envelope()
        val windowSize = max(1, (sampleRate * result.windowSeconds).toInt())
        result.levels += rmsWindows(samples, windowSize)
        if (result.levels.isEmpty()) return result

        result.noiseFloor = percentile(result.levels, 0.05)
        result.reference = percentile(result.levels, 0.85)
        result.threshold = maxOf(0.0025, min(result.noiseFloor * 2.6, result.reference * 0.28), result.reference * 0.1)
        val sustain = max(4, Math.round(1.5 / result.windowSeconds).toInt())
        var index = 0
        while (index + sustain <= result.levels.size) {
            var active = 0
            var peak = 0.0
            for (cursor in index until index + sustain) {
                if (result.levels[cursor] >= result.threshold) active++
                peak = max(peak, result.levels[cursor])
            }
            if (active < sustain * 2 / 3 || peak < result.threshold * 1.45) {
                index++
                continue
            }
            result.audibleStart = max(0.0, index * result.windowSeconds - 0.1)
            val local = average(result.levels, index, index + sustain)
            result.pickupConfidence = clamp((local - result.noiseFloor) / max(1e-6, result.reference - result.noiseFloor), 0.0, 1.0)
            break
        }

        result.contentEnd = duration
        val silenceThreshold = max(0.0015, min(result.threshold * 0.25, result.reference * 0.04))
        var quietStart = result.levels.size
        while (quietStart > 0 && result.levels[quietStart - 1] < silenceThreshold) quietStart--
        val trailingSilence = duration - quietStart * result.windowSeconds
        if (trailingSilence >= 0.35) {
            result.contentEnd = max(0.0, quietStart * result.windowSeconds)
        } else {
            var end = result.levels.size
            while (end > sustain) {
                val start = end - sustain
                var active = 0
                for (cursor in start until end) if (result.levels[cursor] >= result.threshold) active++
                if (active >= sustain / 2 && average(result.levels, start, end) >= result.threshold * 0.85) {
                    result.contentEnd = min(duration, end * result.windowSeconds)
                    break
                }
                end--
            }
        }
        return result
    }

    private fun findMixOutTime(samples: FloatArray, sampleRate: Double, duration: Double, envelope: Envelope): Double {
        val windowSeconds = 0.05
        val windowSize = max(1, (sampleRate * windowSeconds).toInt())
        val levels = rmsWindows(samples, windowSize)
        if (levels.isEmpty()) return envelope.contentEnd

        val silenceThreshold = max(0.0015, min(envelope.threshold * 0.25, envelope.reference * 0.04))
        val searchStart = min(levels.size, (duration * 0.55 / windowSeconds).toInt())
        val contextWindows = (2.0 / windowSeconds).toInt()
        val recoveryWindows = max(1, Math.round(3.0 / windowSeconds).toInt())
        var bestIndex = 0
        var bestDuration = 0.0

        var index = searchStart
        while (index < levels.size) {
            if (levels[index] >= silenceThreshold) {
                index++
                continue
            }
            var end = index + 1
            while (end < levels.size && levels[end] < silenceThreshold) end++
            val silenceDuration = (end - index) * windowSeconds
            val silenceEnd = end * windowSeconds
            if (silenceDuration >= 0.3 && silenceEnd <= duration - 4.0) {
                val beforeStart = if (index > contextWindows) index - contextWindows else 0
                val afterEnd = min(levels.size, end + contextWindows)
                val beforePeak = if (index > beforeStart) (beforeStart until index).maxOf { levels[it] } else 0.0
                val afterPeak = if (afterEnd > end) (end until afterEnd).maxOf { levels[it] } else 0.0
                val quietLevel = average(levels, index, end)
                val earlyGap = index * windowSeconds < envelope.contentEnd * 0.8
                if (beforePeak >= silenceThreshold * 2 &&
                    afterPeak >= silenceThreshold * 2 &&
                    (!earlyGap || !hasMaterialRecovery(levels, end, recoveryWindows, envelope.reference, quietLevel)) &&
                    silenceDuration > bestDuration
                ) {
                    bestIndex = index
                    bestDuration = silenceDuration
                }
            }
            index = end
        }
        if (bestIndex == 0) return envelope.contentEnd

        val cliffThreshold = max(silenceThreshold * 2, envelope.reference * 0.65)
        val maximumBacktrack = (4.0 / windowSeconds).toInt()
        var cliffStart = bestIndex
        while (cliffStart > searchStart && bestIndex - cliffStart < maximumBacktrack && levels[cliffStart - 1] < cliffThreshold) cliffStart--
        return cliffStart * windowSeconds
    }

    private fun nearestDownbeat(downbeats: List<Double>, target: Double, fallback: Double): Double {
        if (downbeats.isEmpty()) return fallback
        val found = lowerBound(downbeats, target)
        if (found == 0) return downbeats[0]
        if (found == downbeats.size) return downbeats.last()
        return if (target - downbeats[found - 1] <= downbeats[found] - target) downbeats[found - 1] else downbeats[found]
    }

    private fun downbeatAtOrBefore(downbeats: List<Double>, target: Double, fallback: Double): Double {
        if (downbeats.isEmpty()) return fallback
        val found = upperBound(downbeats, target)
        return if (found == 0) downbeats[0] else downbeats[found - 1]
    }

    private fun lowerBound(values: List<Double>, target: Double): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(values: List<Double>, target: Double): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun vocalProbabilityFrom(low: Double, vocal: Double, high: Double, flatness: Double): Double {
        val total = low + vocal + high
        val midRatio = vocal / max(1e-12, total)
        val lowRatio = low / max(1e-12, total)
        val score = -2.4 + 5.2 * midRatio - 0.8 * lowRatio + 0.6 * flatness
        return clamp(1.0 / (1.0 + exp(-score)), 0.0, 1.0)
    }

    private val MajorProfile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MinorProfile = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val KeyNames = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    private fun analyzeKeyAndTimbre(
        samples: FloatArray,
        sampleRate: Double,
        startTime: Double,
        endTime: Double,
        result: Result,
        lowFrames: MutableList<EnergyPoint>,
        vocalFrames: MutableList<EnergyPoint>,
    ) {
        val frameSize = 4096
        val hopSize = max(frameSize, (sampleRate * 0.65).toInt())
        val firstSample = min(samples.size, (startTime * sampleRate).toInt())
        val finalSample = min(samples.size, (endTime * sampleRate).toInt())
        val chroma = DoubleArray(12)
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        var chromaWeight = 0.0
        var lowEnergy = 0.0
        var vocalEnergy = 0.0
        var highEnergy = 0.0
        var flatnessTotal = 0.0
        var acceptedFrames = 0

        var start = firstSample
        while (start + frameSize <= finalSample) {
            var squareSum = 0.0
            for (index in 0 until frameSize) {
                val value = samples[start + index].toDouble()
                squareSum += value * value
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / (frameSize - 1))
                re[index] = value * window
                im[index] = 0.0
            }
            val rms = sqrt(squareSum / frameSize)
            if (rms < 0.0025) {
                start += hopSize
                continue
            }
            fft(re, im)

            var frameChroma = 0.0
            var logSum = 0.0
            var arithmeticSum = 0.0
            var flatnessBins = 0
            var frameLow = 0.0
            var frameVocal = 0.0
            var frameHigh = 0.0
            for (bin in 1 until frameSize / 2) {
                val frequency = bin * sampleRate / frameSize
                if (frequency < 45 || frequency > min(5000.0, sampleRate * 0.48)) continue
                val power = re[bin] * re[bin] + im[bin] * im[bin]
                val perceptual = ln1p(power)
                if (frequency < 250) {
                    frameLow += perceptual
                } else if (frequency <= 4000) {
                    frameVocal += perceptual
                    logSum += ln(max(1e-12, power))
                    arithmeticSum += power
                    flatnessBins++
                } else {
                    frameHigh += perceptual
                }
                if (frequency > 5000) continue
                val midi = Math.round(69.0 + 12.0 * log2(frequency / 440.0)).toInt()
                val pitchClass = (midi % 12 + 12) % 12
                val weight = ln1p(power)
                chroma[pitchClass] += weight * rms
                frameChroma += weight
            }
            val frameFlatness = if (flatnessBins > 0 && arithmeticSum > 0) {
                exp(logSum / flatnessBins) / (arithmeticSum / flatnessBins)
            } else {
                0.0
            }
            flatnessTotal += frameFlatness
            lowEnergy += frameLow
            vocalEnergy += frameVocal
            highEnergy += frameHigh
            val centre = (start + frameSize / 2.0) / sampleRate
            lowFrames += EnergyPoint(centre, frameLow)
            vocalFrames += EnergyPoint(centre, vocalProbabilityFrom(frameLow, frameVocal, frameHigh, frameFlatness))
            chromaWeight += max(1e-9, frameChroma * rms)
            acceptedFrames++
            start += hopSize
        }

        val chromaSum = chroma.sum()
        result.chroma = if (chromaSum > 0) DoubleArray(12) { chroma[it] / chromaSum } else chroma

        val candidates = ArrayList<Pair<Double, String>>()
        for (root in 0 until 12) {
            var majorScore = 0.0
            var minorScore = 0.0
            for (pitch in 0 until 12) {
                majorScore += result.chroma[pitch] * MajorProfile[(pitch + 12 - root) % 12]
                minorScore += result.chroma[pitch] * MinorProfile[(pitch + 12 - root) % 12]
            }
            candidates += majorScore to "${KeyNames[root]} major"
            candidates += minorScore to "${KeyNames[root]} minor"
        }
        // std::greater on pairs: by score, ties by name, both descending.
        candidates.sortWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second })
        if (chromaWeight > 0 && candidates.isNotEmpty()) {
            result.key = candidates[0].second
            result.keyConfidence = clamp((candidates[0].first - candidates[1].first) / max(0.01, candidates[0].first) * 4.0, 0.0, 1.0)
        }
        result.vocalProbability = vocalProbabilityFrom(lowEnergy, vocalEnergy, highEnergy, flatnessTotal / max(1, acceptedFrames))
    }

    private fun buildStructure(envelope: Envelope, result: Result) {
        val phraseSeconds = if (result.beatInterval > 0) result.beatInterval * 32.0 else 16.0
        val phraseStart = if (result.downbeats.isNotEmpty()) result.downbeats.first() else envelope.audibleStart
        val firstWindow = (envelope.audibleStart / envelope.windowSeconds).toInt()
        val fourSeconds = max(1, (4.0 / envelope.windowSeconds).toInt())
        val quietWindows = max(1, Math.round(3.0 / envelope.windowSeconds).toInt())
        val recoveryWindows = max(1, Math.round(3.0 / envelope.windowSeconds).toInt())
        var strongWindow = firstWindow
        var index = firstWindow
        while (index + fourSeconds <= envelope.levels.size) {
            if (average(envelope.levels, index, index + fourSeconds) >= envelope.reference * 0.62) {
                strongWindow = index
                break
            }
            index++
        }
        val rawIntro = max(phraseStart + phraseSeconds, strongWindow * envelope.windowSeconds)
        result.introEndTime = clamp(nearestDownbeat(result.downbeats, rawIntro, rawIntro), envelope.audibleStart, min(envelope.contentEnd, 48.0))

        var rawOutro = max(result.introEndTime, envelope.contentEnd - phraseSeconds)
        val searchStart = (max(result.introEndTime, envelope.contentEnd * 0.6) / envelope.windowSeconds).toInt()
        index = searchStart
        while (index + fourSeconds < envelope.levels.size) {
            val sectionAverage = average(envelope.levels, index, index + fourSeconds)
            val tailAverage = average(envelope.levels, index, envelope.levels.size)
            if (sectionAverage >= envelope.reference * 0.68 || tailAverage >= envelope.reference * 0.72) {
                index++
                continue
            }
            if (!hasQuietThenRecovery(envelope.levels, index, max(quietWindows, recoveryWindows), envelope.reference)) {
                rawOutro = index * envelope.windowSeconds
                break
            }
            index++
        }
        result.outroStartTime = clamp(nearestDownbeat(result.downbeats, rawOutro, rawOutro), result.introEndTime, envelope.contentEnd)

        val boundaries = result.phraseBoundaries
        boundaries += phraseStart
        var time = phraseStart + phraseSeconds
        while (time < envelope.contentEnd) {
            boundaries += time
            time += phraseSeconds
        }
        boundaries += result.introEndTime
        boundaries += result.outroStartTime
        if (boundaries.isEmpty() || boundaries.last() < envelope.contentEnd - 0.05) boundaries += envelope.contentEnd
        boundaries.sort()
        // std::unique: drop each value within 0.05 s of the one kept before it.
        val unique = ArrayList<Double>()
        for (value in boundaries) if (unique.isEmpty() || abs(unique.last() - value) >= 0.05) unique += value
        boundaries.clear()
        boundaries += unique
        for (i in 0 until boundaries.size - 1) {
            val start = boundaries[i]
            val end = boundaries[i + 1]
            val energyStart = (start / envelope.windowSeconds).toInt()
            val energyEnd = ceil(end / envelope.windowSeconds).toInt()
            val energy = average(envelope.levels, energyStart, energyEnd)
            val type = when {
                end <= result.introEndTime + 0.1 -> "intro"
                start >= result.outroStartTime - 0.1 -> "outro"
                energy < envelope.reference * 0.58 -> "breakdown"
                else -> "body"
            }
            result.phrases += Phrase(start, end, type, clamp(energy / max(1e-6, envelope.reference), 0.0, 1.0))
        }

        val eightBarTarget = if (result.beatInterval > 0) phraseStart + result.beatInterval * 32.0 else result.introEndTime
        val latestCue = max(envelope.audibleStart, min(36.0, envelope.contentEnd * 0.28))
        val boundedTarget = min(latestCue, eightBarTarget)
        result.mixInTime = clamp(downbeatAtOrBefore(result.downbeats, boundedTarget, boundedTarget), envelope.audibleStart, latestCue)
        val cueWindow = (result.mixInTime / envelope.windowSeconds).toInt()
        val cueEnergy = average(envelope.levels, cueWindow, cueWindow + fourSeconds)
        result.mixInConfidence = clamp(
            result.beatConfidence * 0.65 + clamp(cueEnergy / max(1e-6, envelope.reference), 0.0, 1.0) * 0.35,
            0.0,
            1.0,
        )

        val mixIns = ArrayList<MixCandidate>()
        mixIns += MixCandidate(result.audibleStartTime, 0.8, "pickup")
        if (result.mixInTime > result.audibleStartTime + 0.1) mixIns += MixCandidate(result.mixInTime, 0.9, "intro_drop")
        val dropCue = if (result.beatInterval > 0) phraseStart + result.beatInterval * 32.0 else result.introEndTime
        if (dropCue > result.mixInTime + 0.5 && dropCue < envelope.contentEnd * 0.4) {
            mixIns += MixCandidate(downbeatAtOrBefore(result.downbeats, dropCue, dropCue), 0.95, "main_drop")
        }
        result.mixInCandidates = mixIns

        val mixOuts = ArrayList<MixCandidate>()
        if (result.mixOutTime > 0 && result.mixOutTime < envelope.contentEnd - 1.0) {
            mixOuts += MixCandidate(result.mixOutTime, 0.95, "energy_cliff")
        }
        mixOuts += MixCandidate(result.outroStartTime, 0.9, "outro_start")
        mixOuts += MixCandidate(envelope.contentEnd, 0.75, "content_end")
        result.mixOutCandidates = mixOuts
    }

    /** Analyses mono [samples] at [sampleRate]; empty input gives the empty result. */
    fun analyze(samples: FloatArray, sampleRate: Double = SampleRate, suppliedDuration: Double = 0.0): Features {
        val result = Result()
        result.duration = if (suppliedDuration > 0) suppliedDuration else samples.size / sampleRate
        if (samples.isEmpty() || sampleRate < 1000 || result.duration <= 0) return Features(duration = result.duration)
        val envelope = analyzeEnvelope(samples, sampleRate, result.duration)
        result.audibleStartTime = envelope.audibleStart
        result.pickupTime = envelope.audibleStart
        result.pickupConfidence = envelope.pickupConfidence
        result.contentEndTime = envelope.contentEnd
        result.mixOutTime = findMixOutTime(samples, sampleRate, result.duration, envelope)

        val tempo = analyzeTempo(samples, sampleRate, result.duration, envelope.audibleStart)
        result.bpm = tempo.bpm
        result.beatInterval = tempo.beatInterval
        result.firstBeat = tempo.firstBeat
        result.beatConfidence = tempo.confidence
        result.beats = tempo.beats
        result.downbeats = tempo.downbeats

        var squareSum = 0.0
        var peak = 0.0
        val contentStart = min(samples.size, (envelope.audibleStart * sampleRate).toInt())
        val contentEnd = min(samples.size, (envelope.contentEnd * sampleRate).toInt())
        for (i in contentStart until contentEnd) {
            val v = samples[i].toDouble()
            squareSum += v * v
            peak = max(peak, abs(v))
        }
        val rms = sqrt(squareSum / max(1, contentEnd - contentStart))
        result.loudnessLufs = max(-70.0, toDb(rms) - 0.691)
        result.peakDbfs = toDb(peak)
        result.dynamicRangeDb = clamp(toDb(percentile(envelope.levels, 0.95)) - toDb(percentile(envelope.levels, 0.2)), 0.0, 70.0)

        val curveStride = max(1, (envelope.levels.size + 239) / 240)
        var index = 0
        while (index < envelope.levels.size) {
            val time = index * envelope.windowSeconds
            val norm = clamp(envelope.levels[index] / max(1e-6, envelope.reference), 0.0, 1.5)
            result.energyCurve += EnergyPoint(time, norm)
            index += curveStride
        }
        val lowFrames = ArrayList<EnergyPoint>()
        val vocalFrames = ArrayList<EnergyPoint>()
        analyzeKeyAndTimbre(samples, sampleRate, envelope.audibleStart, envelope.contentEnd, result, lowFrames, vocalFrames)

        val lowReference = percentile(lowFrames.map { it.energy }, 0.85)
        var lowCursor = 0
        for (point in result.energyCurve) {
            while (lowCursor + 1 < lowFrames.size &&
                abs(lowFrames[lowCursor + 1].time - point.time) < abs(lowFrames[lowCursor].time - point.time)
            ) lowCursor++
            val energy = if (lowFrames.isEmpty() || point.energy <= 0.1 || lowReference <= 1e-9) 0.0
            else clamp(lowFrames[lowCursor].energy / lowReference, 0.0, 1.5)
            result.lowEnergyCurve += EnergyPoint(point.time, energy)
        }
        var frameCursor = 0
        for (point in result.energyCurve) {
            while (frameCursor + 1 < vocalFrames.size &&
                abs(vocalFrames[frameCursor + 1].time - point.time) < abs(vocalFrames[frameCursor].time - point.time)
            ) frameCursor++
            val probability = if (vocalFrames.isEmpty()) result.vocalProbability else vocalFrames[frameCursor].energy
            result.vocalActivityMask += clamp(probability * (if (point.energy > 0.25) 1.0 else 0.3), 0.0, 1.0)
        }
        buildStructure(envelope, result)

        return Features(
            duration = result.duration,
            bpm = result.bpm,
            beatInterval = result.beatInterval,
            firstBeat = result.firstBeat,
            beatConfidence = result.beatConfidence,
            key = result.key,
            keyConfidence = result.keyConfidence,
            audibleStartTime = result.audibleStartTime,
            pickupTime = result.pickupTime,
            introEndTime = result.introEndTime,
            outroStartTime = result.outroStartTime,
            contentEndTime = result.contentEndTime,
            mixInTime = result.mixInTime,
            mixOutTime = result.mixOutTime,
            vocalProbability = result.vocalProbability,
            beats = result.beats,
            downbeats = result.downbeats,
            phraseBoundaries = result.phraseBoundaries.toList(),
            vocalActivityMask = result.vocalActivityMask.toList(),
            energyCurve = result.energyCurve.toList(),
            lowEnergyCurve = result.lowEnergyCurve.toList(),
            mixInCandidates = result.mixInCandidates,
            mixOutCandidates = result.mixOutCandidates,
            loudnessLufs = result.loudnessLufs,
        )
    }

    // Tempo: autocorrelation over a spectral-flux onset envelope, then a phase-locked loop.

    private const val MaxEnvelopeSeconds = 1200.0
    private const val MaxTempoSearchSeconds = 180.0
    private const val LowBandHz = 150.0

    private class Onsets(val full: DoubleArray, val low: DoubleArray)

    private fun normalizeEnvelope(envelope: DoubleArray, framesPerSecond: Double) {
        if (envelope.isEmpty()) return
        val radius = max(2, (framesPerSecond * 0.35).toInt())
        val prefix = DoubleArray(envelope.size + 1)
        for (i in envelope.indices) prefix[i + 1] = prefix[i] + envelope[i]
        for (i in envelope.indices) {
            val left = if (i > radius) i - radius else 0
            val right = min(envelope.size, i + radius + 1)
            val localMean = (prefix[right] - prefix[left]) / max(1, right - left)
            envelope[i] = max(0.0, envelope[i] - localMean * 1.08)
        }
        val peak = envelope.max()
        if (peak > 0) for (i in envelope.indices) envelope[i] = sqrt(envelope[i] / peak)
    }

    private fun onsetEnvelope(samples: FloatArray, sampleRate: Double, frameSize: Int, hopSize: Int): Onsets {
        val maximumSamples = min(samples.size, (sampleRate * MaxEnvelopeSeconds).toInt())
        if (maximumSamples < frameSize) return Onsets(DoubleArray(0), DoubleArray(0))
        val frameCount = 1 + (maximumSamples - frameSize) / hopSize
        val lowBandBins = min(frameSize / 2, max(2, (LowBandHz * frameSize / sampleRate).toInt()))
        val full = DoubleArray(frameCount)
        val low = DoubleArray(frameCount)
        val previous = DoubleArray(frameSize / 2)
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (frameSize - 1)) }
        for (frame in 0 until frameCount) {
            val start = frame * hopSize
            for (i in 0 until frameSize) {
                re[i] = samples[start + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            var flux = 0.0
            var lowFlux = 0.0
            for (bin in 1 until frameSize / 2) {
                val magnitude = ln1p(sqrt(re[bin] * re[bin] + im[bin] * im[bin]))
                val rise = max(0.0, magnitude - previous[bin])
                flux += rise
                if (bin < lowBandBins) lowFlux += rise
                previous[bin] = magnitude
            }
            full[frame] = flux
            low[frame] = lowFlux
        }
        val framesPerSecond = sampleRate / hopSize
        normalizeEnvelope(full, framesPerSecond)
        normalizeEnvelope(low, framesPerSecond)
        return Onsets(full, low)
    }

    private fun correlation(values: DoubleArray, lag: Int, limit: Int): Double {
        val length = min(limit, values.size)
        if (lag <= 0 || lag >= length) return 0.0
        var cross = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (i in lag until length) {
            val left = values[i]
            val right = values[i - lag]
            cross += left * right
            leftEnergy += left * left
            rightEnergy += right * right
        }
        return cross / sqrt(max(1e-12, leftEnergy * rightEnergy))
    }

    private fun sampleEnvelope(values: DoubleArray, position: Double): Double {
        if (position < 0 || position >= values.size - 1) return 0.0
        val left = position.toInt()
        val fraction = position - left
        return values[left] * (1.0 - fraction) + values[left + 1] * fraction
    }

    private fun metricalPrior(bpm: Double): Double {
        if (!(bpm > 0)) return 0.0
        val octaves = log2(bpm / 120.0) / 0.7
        return exp(-0.5 * octaves * octaves)
    }

    private class TrackedGrid(val beats: List<Double>, val intervals: List<Double>)

    fun analyzeTempo(samples: FloatArray, sampleRate: Double, duration: Double, audibleStart: Double): Tempo {
        val frameSize = 512
        val hopSize = 128
        val onsets = onsetEnvelope(samples, sampleRate, frameSize, hopSize)
        val envelope = onsets.full
        if (envelope.size < 64) return Tempo()

        val framesPerSecond = sampleRate / hopSize
        val searchLimit = min(envelope.size, (framesPerSecond * MaxTempoSearchSeconds).toInt())
        val minimumLag = max(2, floor(framesPerSecond * 60.0 / 200.0).toInt())
        val maximumLag = ceil(framesPerSecond * 60.0 / 70.0).toInt()
        val scores = DoubleArray(maximumLag + 1)
        var bestLag = minimumLag
        for (lag in minimumLag..maximumLag) {
            val bpm = framesPerSecond * 60.0 / lag
            val tempoPrior = exp(-((bpm - 118.0) / 75.0).pow(2.0))
            scores[lag] = correlation(envelope, lag, searchLimit) + 0.42 * correlation(envelope, lag * 2, searchLimit) + 0.08 * tempoPrior
            if (scores[lag] > scores[bestLag]) bestLag = lag
        }

        run {
            var bestMetrical = -1.0
            var metricalLag = bestLag
            for (ratio in doubleArrayOf(0.5, 1.0, 2.0)) {
                val candidate = (bestLag * ratio).roundToLong().toInt()
                if (candidate < minimumLag || candidate > maximumLag) continue
                val bpm = framesPerSecond * 60.0 / candidate
                val score = correlation(envelope, candidate, searchLimit) * metricalPrior(bpm)
                if (score > bestMetrical) {
                    bestMetrical = score
                    metricalLag = candidate
                }
            }
            bestLag = metricalLag
        }

        var refinedLag = bestLag.toDouble()
        if (bestLag in (minimumLag + 1) until maximumLag) {
            val left = correlation(envelope, bestLag - 1, searchLimit)
            val center = correlation(envelope, bestLag, searchLimit)
            val right = correlation(envelope, bestLag + 1, searchLimit)
            val denominator = left - 2.0 * center + right
            if (abs(denominator) > 1e-9) refinedLag += clamp(0.5 * (left - right) / denominator, -0.5, 0.5)
        }

        fun estimatePhase(lag: Double, limit: Int): Pair<Int, Double> {
            val phaseCount = max(1, Math.round(lag).toInt())
            val end = min(limit, envelope.size).toDouble()
            var bestScore = -1.0
            var best = 0
            for (phase in 0 until phaseCount) {
                var score = 0.0
                var count = 0
                var position = phase.toDouble()
                while (position < end) {
                    score += sampleEnvelope(envelope, position)
                    count++
                    position += lag
                }
                score /= max(1, count)
                if (score > bestScore) {
                    bestScore = score
                    best = phase
                }
            }
            return best to bestScore
        }

        var (bestPhase, bestPhaseScore) = estimatePhase(refinedLag, (framesPerSecond * 30.0).toInt())
        var firstBeat = 0.0
        fun anchorFirstBeat(phase: Int, lag: Double) {
            val intervalSeconds = lag / framesPerSecond
            var first = phase / framesPerSecond
            while (first + intervalSeconds < audibleStart - 0.15) first += intervalSeconds
            while (first > audibleStart + intervalSeconds) first -= intervalSeconds
            firstBeat = max(0.0, first)
        }
        anchorFirstBeat(bestPhase, refinedLag)

        val envelopeEnd = envelope.size.toDouble() - 1
        val lastFrame = duration * framesPerSecond

        fun track(startLag: Double): TrackedGrid {
            val beats = ArrayList<Double>()
            val intervals = ArrayList<Double>()
            val searchRadius = startLag * 0.25
            val phaseGain = 0.20
            val intervalGain = 0.01
            val maxIntervalDrift = startLag * 0.03
            var position = firstBeat * framesPerSecond
            var interval = startLag
            while (position <= lastFrame + 1e-6) {
                beats += max(0.0, position)
                val predicted = position + interval
                if (predicted > lastFrame + 1e-6) break
                if (predicted + searchRadius < envelopeEnd) {
                    var bestValue = -1.0
                    var bestOffset = 0.0
                    val low = floor(predicted - searchRadius).toInt()
                    val high = ceil(predicted + searchRadius).toInt()
                    var frame = max(0, low)
                    while (frame <= high && frame < envelope.size) {
                        if (envelope[frame] > bestValue) {
                            bestValue = envelope[frame]
                            bestOffset = frame.toDouble()
                        }
                        frame++
                    }
                    if (bestValue > 0.15) {
                        val index = bestOffset.toInt()
                        if (index > 0 && index + 1 < envelope.size) {
                            val left = envelope[index - 1]
                            val center = envelope[index]
                            val right = envelope[index + 1]
                            val denominator = left - 2.0 * center + right
                            if (abs(denominator) > 1e-9) bestOffset += clamp(0.5 * (left - right) / denominator, -0.5, 0.5)
                        }
                        val error = bestOffset - predicted
                        position = predicted + phaseGain * error
                        interval = clamp(interval + intervalGain * error, startLag - maxIntervalDrift, startLag + maxIntervalDrift)
                        intervals += interval
                        continue
                    }
                }
                position = predicted
            }
            return TrackedGrid(beats, intervals)
        }

        val learning = track(refinedLag)
        var grid = learning
        if (learning.intervals.size >= 8) {
            val sorted = learning.intervals.sorted()
            val locked = sorted[sorted.size / 2]
            if (locked > 0) refinedLag = locked
            val relocked = estimatePhase(refinedLag, envelope.size)
            bestPhase = relocked.first
            bestPhaseScore = relocked.second
            anchorFirstBeat(bestPhase, refinedLag)
            grid = track(refinedLag)
        }
        val beats = grid.beats.map { it / framesPerSecond }.toMutableList()
        val bpm = framesPerSecond * 60.0 / refinedLag
        val beatInterval = 60.0 / bpm

        var downbeatOffset = 0
        var downbeatScore = -1.0
        for (offset in 0 until 4) {
            var lowScore = 0.0
            var fullScore = 0.0
            var count = 0
            var beat = offset
            while (beat < beats.size && beat < 256) {
                val positionFrames = beats[beat] * framesPerSecond
                lowScore += sampleEnvelope(onsets.low, positionFrames)
                fullScore += sampleEnvelope(envelope, positionFrames)
                count++
                beat += 4
            }
            val score = (lowScore + 0.4 * fullScore) / max(1, count)
            if (score > downbeatScore) {
                downbeatScore = score
                downbeatOffset = offset
            }
        }
        val downbeats = ArrayList<Double>()
        var beat = downbeatOffset
        while (beat < beats.size) {
            downbeats += beats[beat]
            beat += 4
        }

        val frameCentre = frameSize / (2.0 * sampleRate)
        firstBeat += frameCentre
        for (i in beats.indices) beats[i] += frameCentre
        for (i in downbeats.indices) downbeats[i] += frameCentre

        var runnerUp = 0.0
        for (lag in minimumLag..maximumLag) if (abs(lag - bestLag) > 2) runnerUp = max(runnerUp, scores[lag])
        val separation = (scores[bestLag] - runnerUp) / max(0.05, scores[bestLag])
        val confidence = clamp(0.35 * scores[bestLag] + 0.35 * bestPhaseScore + 0.3 * max(0.0, separation), 0.0, 1.0)
        if (!bpm.isFinite() || bpm < 60 || bpm > 220) return Tempo()
        return Tempo(bpm, beatInterval, firstBeat, confidence, beats, downbeats)
    }
}
