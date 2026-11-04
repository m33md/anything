/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

/** The phone's creative transition styles (its dj/TransitionStyle). */
enum class CreativeStyle {
    TRANSPARENT,
    REVERB_BLOOM,
    DELAY_BLEND,
    ECHO_FREEZE,
    TAPE_STOP,
}

/**
 * The phone's TransitionSelector: walks a fixed sequence of styles so mixes vary, never repeats
 * the last one, and keeps the tape stop for confident grids going into a song that starts strong.
 */
internal object CreativeSelector {
    fun select(
        tier: DjMixTier,
        outgoing: TrackAnalysis?,
        incoming: TrackAnalysis?,
        creativeEnabled: Boolean,
        recentStyles: List<CreativeStyle>,
        transitionIndex: Long,
    ): CreativeStyle {
        if (!creativeEnabled || outgoing == null) return CreativeStyle.TRANSPARENT
        val selected = Sequence[Math.floorMod(transitionIndex, Sequence.size.toLong()).toInt()]
        if (selected == CreativeStyle.TAPE_STOP) {
            val confident = outgoing.beatConfidence
            val energeticIntro = incoming?.let { introEnergy(it) } ?: 0.5
            if (tier == DjMixTier.PlainCrossfade || confident < 0.45 || energeticIntro < 0.4) {
                return if (recentStyles.firstOrNull() == CreativeStyle.DELAY_BLEND) CreativeStyle.REVERB_BLOOM else CreativeStyle.DELAY_BLEND
            }
        }
        return if (selected == recentStyles.firstOrNull()) {
            if (selected == CreativeStyle.REVERB_BLOOM) CreativeStyle.DELAY_BLEND else CreativeStyle.REVERB_BLOOM
        } else {
            selected
        }
    }

    /**
     * The phone's EnergyProfile.introEnergy: the first eight seconds' level against the song's
     * usual level (its upper quartile), 0..1. A song too short to judge counts as quiet.
     */
    fun introEnergy(analysis: TrackAnalysis): Double {
        val curve = analysis.energyCurve
        if (curve.isEmpty() || analysis.duration < MinUsableSeconds) return 0.0
        val sorted = curve.map { it.energy }.sorted()
        val reference = sorted[(sorted.size * 3 / 4).coerceAtMost(sorted.lastIndex)]
        if (reference <= 0.0) return 0.0
        val intro = curve.filter { it.time < EdgeSeconds }.map { it.energy }
        if (intro.isEmpty()) return 0.0
        return (intro.average() / reference).coerceIn(0.0, 1.0)
    }

    private const val EdgeSeconds = 8.0
    private const val MinUsableSeconds = 20.0

    private val Sequence = arrayOf(
        CreativeStyle.REVERB_BLOOM,
        CreativeStyle.DELAY_BLEND,
        CreativeStyle.ECHO_FREEZE,
        CreativeStyle.REVERB_BLOOM,
        CreativeStyle.DELAY_BLEND,
        CreativeStyle.TAPE_STOP,
    )
}
