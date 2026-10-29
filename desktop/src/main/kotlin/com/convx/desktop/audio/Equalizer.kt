/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.audio

import kotlinx.serialization.Serializable

/** The filters AutoEQ's ParametricEQ files use. */
@Serializable
enum class EqFilter {
    /** Peaking: boosts or cuts around the frequency. */
    PK,
    /** Low shelf: everything below. */
    LSC,
    /** High shelf: everything above. */
    HSC,
    /** Low pass. */
    LPQ,
    /** High pass. */
    HPQ,
}

/** One band: a filter at [frequency] Hz, [gain] dB, [q] wide. */
@Serializable
data class EqBand(
    val frequency: Double,
    val gain: Double = 0.0,
    val q: Double = 1.41,
    val type: EqFilter = EqFilter.PK,
    val enabled: Boolean = true,
)

/** A tuning saved under a name, or imported from a ParametricEQ file. */
@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val bands: List<EqBand>,
    val preamp: Double,
)

/**
 * The phone's Convx Equalizer: ten bands from 31 Hz to 16 kHz unless a ParametricEQ file
 * brings its own, a preamp that keeps boosts from clipping, and spatial audio beside it.
 */
@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bands: List<EqBand> = Equalizer.flatBands(),
    /** dB, -24..0. */
    val preamp: Double = 0.0,
    /** Simple (the three-way dial and presets) or Advanced (every band). */
    val advanced: Boolean = false,
    /** Wider stereo with a soft crossfeed, for headphones. */
    val spatialAudio: Boolean = false,
    val presets: List<EqPreset> = emptyList(),
)

object Equalizer {
    val Frequencies = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    /** How far a band goes either way, in dB, as on the phone. */
    const val MaxGain = 12.0
    const val MinPreamp = -24.0

    fun flatBands(): List<EqBand> = Frequencies.map { EqBand(it) }

    /**
     * Ten gains in dB as the standard bands, with the preamp pulled down by the biggest boost
     * so nothing clips: what picking a preset or turning the dial does on the phone.
     */
    fun fromGains(gains: List<Double>): Pair<List<EqBand>, Double> {
        val bands = Frequencies.mapIndexed { i, f -> EqBand(f, gains.getOrElse(i) { 0.0 }.coerceIn(-MaxGain, MaxGain)) }
        return bands to -(bands.maxOf { it.gain.coerceAtLeast(0.0) })
    }

    /** The bands' gains if they're the ten standard ones, for matching presets and the dial. */
    fun standardGains(bands: List<EqBand>): List<Double>? =
        if (bands.size == 10 && bands.zip(Frequencies.toList()).all { (b, f) -> b.frequency == f && b.type == EqFilter.PK }) {
            bands.map { it.gain }
        } else null

    /**
     * The dial's three axes spread over the ten bands, overlapping smoothly, with a little extra
     * at both ends (the phone's applyTriangle). Values are dB.
     */
    fun triangle(bass: Double, mid: Double, treble: Double): List<Double> {
        val b = bass.coerceIn(-MaxGain, MaxGain)
        val m = mid.coerceIn(-MaxGain, MaxGain)
        val t = treble.coerceIn(-MaxGain, MaxGain)
        return listOf(
            b * 1.1,
            b,
            b * 0.7 + m * 0.3,
            b * 0.2 + m * 0.8,
            m,
            m,
            m * 0.8 + t * 0.2,
            m * 0.3 + t * 0.7,
            t,
            t * 1.15,
        )
    }

    /** Reads the dial back off the bands the way the phone does, so it doesn't drift. */
    fun dialOf(gains: List<Double>): Triple<Double, Double, Double> =
        Triple(gains[1], (gains[4] + gains[5]) / 2, gains[8])

    class Builtin(val name: String, val gains: List<Double>)

    class Group(val title: String, val presets: List<Builtin>)

    // The phone's presets, in its units of 1/50 dB.
    private fun preset(name: String, vararg units: Float) = Builtin(name, units.map { it / 50.0 })

    val Groups = listOf(
        Group(
            "Convx Signature",
            listOf(
                preset("Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                preset("Convx Signature", 150f, 100f, 50f, 0f, -20f, 0f, 80f, 150f, 200f, 150f),
                preset("Acoustic", 150f, 150f, 50f, 75f, 100f, 75f, 125f, 175f, 150f, 75f),
                preset("3D Stage", 75f, 50f, 25f, -50f, -25f, 0f, 50f, 75f, 100f, 75f),
                preset("Bass Boost", 500f, 400f, 250f, 100f, 0f, -50f, 0f, 100f, 200f, 300f),
                preset("Pure Clarity", -100f, -50f, 0f, 50f, 150f, 250f, 300f, 250f, 150f, 100f),
                preset("Soft Bass", 200f, 180f, 140f, 80f, 30f, 20f, 60f, 90f, 110f, 130f),
                preset("Electronic", 350f, 280f, 120f, -50f, -150f, 50f, 180f, 300f, 400f, 500f),
                preset("Rock", 300f, 220f, 150f, 50f, -100f, 120f, 200f, 250f, 320f, 380f),
                preset("Pop", -150f, 0f, 100f, 180f, 250f, 220f, 150f, 80f, -50f, -120f),
                preset("Jazz", 150f, 100f, 60f, 140f, 200f, 180f, 120f, 180f, 220f, 200f),
                preset("Voice", -250f, -150f, 0f, 200f, 400f, 380f, 200f, 120f, 0f, -120f),
            ),
        ),
        Group(
            "Dolby Atmos",
            listOf(
                preset("Dolby Open", 150f, 180f, 220f, 180f, 160f, 210f, 250f, 280f, 180f, 80f),
                preset("Dolby Rich", 100f, 160f, 200f, 220f, 280f, 260f, 240f, 200f, 150f, 50f),
                preset("Dolby Focused", -300f, -50f, 130f, 180f, 220f, 120f, 140f, 100f, -50f, -300f),
            ),
        ),
        Group(
            "Dirac Audio",
            listOf(
                preset("Dirac Music", 200f, 140f, 80f, 0f, 30f, 80f, 140f, 200f, 280f, 350f),
                preset("Dirac Movie", 300f, 250f, 150f, 0f, 70f, 120f, 180f, 250f, 320f, 400f),
                preset("Dirac Game", 150f, 250f, 200f, 0f, 80f, 150f, 300f, 450f, 400f, 280f),
            ),
        ),
    )

    /** Whether [gains] are [preset]'s, within the phone's 0.2 dB. */
    fun matches(gains: List<Double>?, preset: List<Double>): Boolean =
        gains != null && gains.size == preset.size && gains.zip(preset).all { (a, b) -> kotlin.math.abs(a - b) < 0.2 }
}

/**
 * AutoEQ's ParametricEQ.txt, as the phone imports it:
 *
 *     Preamp: -5.2 dB
 *     Filter 1: ON LSC Fc 105 Hz Gain 8.8 dB Q 0.70
 */
object ParametricEq {
    private const val MaxBytes = 64 * 1024
    private const val MaxBands = 20

    class Parsed(val preamp: Double, val bands: List<EqBand>)

    /** Throws with a reason the user can read if [text] isn't a usable preset. */
    fun parse(text: String): Parsed {
        require(text.length <= MaxBytes) { "The file is bigger than 64 KB." }
        var preamp = 0.0
        val bands = mutableListOf<EqBand>()
        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trim()
            when {
                line.startsWith("Preamp:", ignoreCase = true) ->
                    preamp = number(line, "Preamp:", "dB") ?: 0.0
                line.startsWith("Filter", ignoreCase = true) -> filter(line)?.let(bands::add)
            }
        }
        require(bands.isNotEmpty()) { "There are no filters in it." }
        require(bands.size <= MaxBands) { "It has ${bands.size} filters; Convx takes up to $MaxBands." }
        require(preamp.isFinite() && preamp in -50.0..50.0) { "Its preamp is out of range." }
        bands.forEachIndexed { i, b ->
            require(b.frequency.isFinite() && b.frequency > 0 && b.frequency <= 100_000) { "Filter ${i + 1}'s frequency is out of range." }
            require(b.gain.isFinite() && b.gain in -30.0..30.0) { "Filter ${i + 1}'s gain is out of range." }
            require(b.q.isFinite() && b.q > 0 && b.q <= 20) { "Filter ${i + 1}'s Q is out of range." }
        }
        return Parsed(preamp, bands)
    }

    private fun filter(line: String): EqBand? {
        // "OFF" lines are switched-off filters.
        if (!Regex("""\bON\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return null
        val type = when {
            Regex("""\bLSC?\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> EqFilter.LSC
            Regex("""\bHSC?\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> EqFilter.HSC
            Regex("""\bPK\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> EqFilter.PK
            Regex("""\bLPQ?\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> EqFilter.LPQ
            Regex("""\bHPQ?\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> EqFilter.HPQ
            else -> return null
        }
        val frequency = number(line, "Fc", "Hz") ?: return null
        val gain = number(line, "Gain", "dB") ?: if (type == EqFilter.LPQ || type == EqFilter.HPQ) 0.0 else return null
        val q = number(line, "Q", null) ?: 0.71
        return EqBand(frequency, gain, q, type)
    }

    private fun number(line: String, key: String, unit: String?): Double? {
        val unitPattern = if (unit != null) """\s*$unit""" else ""
        return Regex("""${Regex.escape(key)}\s*([-+]?\d+(?:\.\d+)?)$unitPattern""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
