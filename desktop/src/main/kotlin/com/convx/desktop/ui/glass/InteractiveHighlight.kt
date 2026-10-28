/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The phone's InteractiveHighlight: an additive sheen with a soft spot of light under the
 * pointer. On the phone it follows a finger while pressed; here the cursor also lights the
 * glass at a third of the strength while it hovers, and a press brings it to full.
 *
 * [modifier] draws the light and [gestureModifier] listens; they are separate so the light can
 * sit on a recorded glass layer while the hit target is the moving element.
 */
class InteractiveHighlight(
    private val scope: CoroutineScope,
    private val radiusScale: Float = 1.5f,
    private val sheenAlpha: Float = 0.08f,
    private val spotAlpha: Float = 0.15f,
    private val hoverStrength: Float = 0.35f,
) {
    private val progressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val progress = Animatable(0f, 0.001f)
    private val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var hovered = false
    private var pressed = false

    val modifier: Modifier = Modifier.drawWithContent {
        val p = progress.value
        if (p > 0f) {
            // Plus lifts what is behind without replacing its detail.
            drawRect(Color.White.copy(alpha = sheenAlpha * p), blendMode = BlendMode.Plus)
            val radius = (size.minDimension * radiusScale).coerceAtLeast(1f)
            val pos = position.value
            val center = Offset(pos.x.coerceIn(0f, size.width), pos.y.coerceIn(0f, size.height))
            val spot = Color.White.copy(alpha = spotAlpha * p)
            drawRect(
                brush = Brush.radialGradient(
                    0f to spot,
                    0.5f to spot,
                    1f to Color.White.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                ),
                blendMode = BlendMode.Plus,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter -> {
                        hovered = true
                        scope.launch { position.snapTo(change.position) }
                        settle()
                    }
                    PointerEventType.Exit -> {
                        hovered = false
                        pressed = false
                        settle()
                    }
                    PointerEventType.Move -> scope.launch {
                        if (pressed) position.snapTo(change.position) else position.animateTo(change.position, positionSpec)
                    }
                    PointerEventType.Press -> {
                        pressed = true
                        scope.launch { position.snapTo(change.position) }
                        settle()
                    }
                    PointerEventType.Release -> {
                        pressed = false
                        settle()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun settle() {
        val target = when {
            pressed -> 1f
            hovered -> hoverStrength
            else -> 0f
        }
        scope.launch { progress.animateTo(target, progressSpec) }
    }
}
