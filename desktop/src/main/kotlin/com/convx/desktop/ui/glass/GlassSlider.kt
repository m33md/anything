/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.convx.desktop.ui.shapes.ContinuousCapsule
import com.convx.desktop.ui.theme.LocalAccent
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest

/** iOS's dark separator, the slider's empty track. */
private val TrackOff = Color(0x99545458)

/**
 * The phone's slider (hig's CupertinoSlider with its liquid thumb): a 6 dp track filled in the
 * accent up to a 40 x 24 white thumb. Pressed or dragged, the thumb swells and turns to clear
 * glass that magnifies the track, squashing with the drag's speed, and it slows as it nears
 * either end instead of stopping dead. Click anywhere on the track to jump there.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val updatedValue by rememberUpdatedState(value)
    val updatedEnabled by rememberUpdatedState(enabled)
    val updatedOnChange by rememberUpdatedState(onValueChange)
    val updatedOnFinished by rememberUpdatedState(onValueChangeFinished)
    val trackBackdrop = rememberLayerBackdrop()
    val accent = LocalAccent.current
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var thumbWidth by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val scope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        var virtualValue by remember { mutableFloatStateOf(value) }
        val span = valueRange.endInclusive - valueRange.start
        val drag = remember(scope, valueRange) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = updatedValue.coerceIn(valueRange),
                valueRange = valueRange,
                visibilityThreshold = span * 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = { virtualValue = targetValue },
                onDragStopped = {
                    if (updatedEnabled && didDrag) {
                        animateToValue(targetValue)
                        updatedOnChange(targetValue)
                        updatedOnFinished?.invoke()
                    }
                    didDrag = false
                },
                onDrag = { _, dragAmount ->
                    if (updatedEnabled) {
                        if (!didDrag) didDrag = dragAmount.x != 0f
                        virtualValue = (virtualValue + span * dragAmount.x / trackWidth).coerceIn(valueRange)
                        updateValue(virtualValue)
                        updatedOnChange(virtualValue)
                    }
                },
            )
        }
        LaunchedEffect(drag) {
            snapshotFlow { updatedValue }.collectLatest { v ->
                if (drag.targetValue != v) drag.updateValue(v)
            }
        }

        // The track, which the thumb's glass bends while pressed; clicking it jumps the thumb there.
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(drag) {
                    detectTapGestures { position ->
                        if (updatedEnabled) {
                            val target = (valueRange.start + span * (position.x / trackWidth)).coerceIn(valueRange)
                            drag.animateToValue(target)
                            updatedOnChange(target)
                            updatedOnFinished?.invoke()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.layerBackdrop(trackBackdrop).fillMaxWidth().alpha(if (enabled) 1f else 0.5f)) {
                Box(Modifier.clip(ContinuousCapsule).background(TrackOff).height(6.dp).fillMaxWidth())
                Box(
                    Modifier
                        .clip(ContinuousCapsule)
                        .background(accent)
                        .height(6.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (constraints.maxWidth * drag.progress).fastRoundToInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        },
                )
            }
        }

        Box(
            Modifier.graphicsLayer {
                thumbWidth = size.width
                // Centred on the value, but eased in at the ends so it never leaves the track.
                val actual = -thumbWidth / 2f + trackWidth * drag.progress
                translationX = when (actual) {
                    in -thumbWidth / 2..thumbWidth / 4 -> (actual + thumbWidth / 2) / 3
                    in trackWidth - thumbWidth * 5 / 4..trackWidth - thumbWidth / 2 ->
                        trackWidth - thumbWidth * 5 / 4 + (actual - (trackWidth - thumbWidth * 5 / 4)) / 3
                    else -> actual
                }
            },
        ) {
            Box(
                Modifier
                    .then(if (enabled) drag.modifier else Modifier)
                    .drawBackdrop(
                        backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = if (enabled) drag.pressProgress else 0f
                            scale(lerp(2f / 3f, 1f, progress), lerp(0f, 1f, progress)) { drawBackdrop() }
                        },
                        shape = { ContinuousCapsule },
                        effects = {
                            val progress = if (enabled) drag.pressProgress else 0f
                            blur(8.dp.toPx() * (1f - progress))
                            lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                        },
                        highlight = {
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = if (enabled) drag.pressProgress else 0f,
                            )
                        },
                        shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = if (enabled) 0.15f else 0.05f)) },
                        innerShadow = {
                            val progress = if (enabled) drag.pressProgress else 0f
                            InnerShadow(radius = 4.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            if (enabled) {
                                scaleX = drag.scaleX
                                scaleY = drag.scaleY
                                val velocity = drag.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = if (enabled) drag.pressProgress else 0f
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        },
                    )
                    .size(40.dp, 24.dp),
            )
        }
    }
}
