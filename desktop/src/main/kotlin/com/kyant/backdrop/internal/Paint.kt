/*
 * Vendored from Kyant0/backdrop v2.0.1 (io.github.kyant0:backdrop-desktop), the
 * desktop build of the glass library the phone app vendors.
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Shipped as source so it builds against this app's Compose and Kotlin. Changes:
 * the Skia actuals are merged into the common declarations (expect/actual
 * removed) and use Compose 1.9's names for the Skia accessors, the lens no longer
 * accepts io.github.kyant0:shapes' RoundedRectangularShape, and IDE-only
 * annotations were dropped.
 */
package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.Paint
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asSkikoRuntimeShader
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius)
        else null
}

internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asSkikoRuntimeShader()?.makeShader()
}
