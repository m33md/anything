/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.core.Easing
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The phone bar's motion (ui/component/floatingtabbar/BarMotion.kt): springs squeezed into
 * fixed-duration easings, so every part of a morph ends on the same frame, with the phone's
 * timings and overshoots.
 */

/**
 * An underdamped spring's step response as an [Easing]: crosses the target early, overshoots
 * by about exp(-zeta*pi/sqrt(1-zeta^2)) and settles. [omegaT] is the natural frequency times
 * the duration; 7.8 leaves under 0.6% residual, removed linearly so transform(1) is exactly 1.
 */
class SpringEasing(private val zeta: Float, private val omegaT: Float = 7.8f) : Easing {
    private val root = sqrt(1f - zeta * zeta)
    private val end = raw(1f)

    private fun raw(t: Float): Float {
        val wd = omegaT * root * t
        return 1f - exp(-zeta * omegaT * t) * (cos(wd) + zeta / root * sin(wd))
    }

    override fun transform(fraction: Float): Float =
        if (fraction >= 1f) 1f else raw(fraction) + (1f - end) * fraction
}

/** Critically damped on the same frame: spring-shaped, front-loaded, never passes the target. */
class CriticalEasing(private val omegaT: Float = 7.8f) : Easing {
    private val end = raw(1f)

    private fun raw(t: Float): Float {
        val wt = omegaT * t
        return 1f - (1f + wt) * exp(-wt)
    }

    override fun transform(fraction: Float): Float =
        if (fraction >= 1f) 1f else raw(fraction) + (1f - end) * fraction
}

object BarMotion {
    /** The phone's expand and collapse morph lengths. */
    const val ExpandMs = 470
    const val CollapseMs = 560

    /** Centre travel for everything: spring-shaped, never overshoots. */
    val Travel = CriticalEasing()

    /** The tab pill's body on expand, about 3% past; its width bounces more, about 25%. */
    val SurfaceExpand = SpringEasing(zeta = 0.74f)
    val SurfaceExpandWidth = SpringEasing(zeta = 0.40f)

    /** Folding back lands with a deeper overshoot than the expand. */
    val FoldCollapse = SpringEasing(zeta = 0.55f)
}
