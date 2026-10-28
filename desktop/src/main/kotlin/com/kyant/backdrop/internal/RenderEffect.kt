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

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaColorFilter
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asSkikoRuntimeShader
import org.jetbrains.skia.ImageFilter

internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        ImageFilter.makeCompose(other.asSkiaImageFilter(), this.asSkiaImageFilter()).asComposeRenderEffect()
    } else {
        other
    }
}

internal fun RuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String
): RenderEffect {
    return ImageFilter.makeRuntimeShader(
        runtimeShader.asSkikoRuntimeShader(),
        uniformShaderName,
        null
    ).asComposeRenderEffect()
}

internal fun ColorFilterEffect(
    renderEffect: RenderEffect? = null,
    colorFilter: ColorFilter
): RenderEffect {
    return ImageFilter.makeColorFilter(
        colorFilter.asSkiaColorFilter(),
        renderEffect?.asSkiaImageFilter(),
        null
    ).asComposeRenderEffect()
}
