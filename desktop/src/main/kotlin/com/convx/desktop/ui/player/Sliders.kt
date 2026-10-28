/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.player.PlayerController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.sin

/**
 * Playback position at frame rate. The controller reports every 100 ms; between
 * reports this runs the clock forward so the lyric sweep and the bar glide instead
 * of stepping.
 */
@Composable
fun rememberSmoothPosition(): State<Long> {
    val smooth = remember { mutableLongStateOf(PlayerController.position.value) }
    LaunchedEffect(Unit) {
        val playing = PlayerController.state.map { it.isPlaying && !it.isLoading }.distinctUntilChanged()
        combine(PlayerController.position, playing) { base, isPlaying -> base to isPlaying }
            .collectLatest { (base, isPlaying) ->
                smooth.longValue = base
                if (!isPlaying) return@collectLatest
                val start = withFrameMillis { it }
                while (true) {
                    withFrameMillis { now ->
                        // Never run more than a little ahead of the last report.
                        smooth.longValue = base + (now - start).coerceAtMost(250)
                    }
                }
            }
    }
    return smooth
}

/**
 * The progress bar. With [wavy] on it is the phone's squiggle: the played part
 * ripples while music plays and lies flat when paused.
 */
@Composable
fun SeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    wavy: Boolean = true,
    playing: Boolean = false,
    activeColor: Color = Color.White,
    hoverColor: Color = activeColor,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    height: Dp = 18.dp,
    strokeWidth: Dp = 4.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val amplitude by animateFloatAsState(if (wavy && playing && dragFraction == null) 1f else 0f, tween(400))
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)))

    val engaged = hovered || dragFraction != null

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    dragFraction = (down.position.x / width).coerceIn(0f, 1f)
                    down.consume()
                    drag(down.id) { change ->
                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                        change.consume()
                    }
                    val target = dragFraction
                    if (target != null && currentDuration > 0) currentOnSeek((target * currentDuration).toLong())
                    dragFraction = null
                }
            },
    ) {
        val fraction = dragFraction ?: if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val stroke = strokeWidth.toPx()
        val cy = size.height / 2
        val inset = stroke / 2
        val usable = size.width - stroke
        val x = inset + usable * fraction
        val color = if (engaged) hoverColor else activeColor

        // Remaining track
        drawLine(trackColor, Offset(x, cy), Offset(size.width - inset, cy), stroke, StrokeCap.Round)

        // Played part
        val amp = 2.6.dp.toPx() * amplitude
        if (amp > 0.3f && x - inset > 2f) {
            val wavelength = 26.dp.toPx()
            val path = Path()
            path.moveTo(inset, cy)
            var px = inset
            val step = 2f
            while (px < x) {
                val t = (px - inset) / wavelength - phase
                // Fade the wave in from the left edge and out at the head, so it meets the thumb flat.
                val headFade = ((x - px) / (wavelength * 0.5f)).coerceIn(0f, 1f)
                path.lineTo(px, cy + amp * headFade * sin(2 * PI * t).toFloat())
                px += step
            }
            path.lineTo(x, cy)
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        } else {
            drawLine(color, Offset(inset, cy), Offset(x, cy), stroke, StrokeCap.Round)
        }

        if (engaged) {
            drawCircle(Color.White, radius = 6.5.dp.toPx(), center = Offset(x, cy))
        }
    }
}

/** Plain slider for volume. */
@Composable
fun ValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    hoverColor: Color = activeColor,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    height: Dp = 18.dp,
    strokeWidth: Dp = 4.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val currentOnChange by rememberUpdatedState(onValueChange)
    val engaged = hovered || dragging
    Canvas(
        modifier
            .height(height)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    dragging = true
                    currentOnChange((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()
                    drag(down.id) { change ->
                        currentOnChange((change.position.x / width).coerceIn(0f, 1f))
                        change.consume()
                    }
                    dragging = false
                }
            },
    ) {
        val stroke = strokeWidth.toPx()
        val cy = size.height / 2
        val inset = stroke / 2
        val x = inset + (size.width - stroke) * value.coerceIn(0f, 1f)
        drawLine(trackColor, Offset(inset, cy), Offset(size.width - inset, cy), stroke, StrokeCap.Round)
        drawLine(if (engaged) hoverColor else activeColor, Offset(inset, cy), Offset(x, cy), stroke, StrokeCap.Round)
        if (engaged) drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, cy))
    }
}
