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
package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

@Stable
fun emptyBackdrop(): Backdrop = EmptyBackdrop

@Immutable
private object EmptyBackdrop : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
    }
}
