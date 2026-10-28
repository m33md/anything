/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.convx.desktop.ui.shapes.ContinuousCapsule
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

/** iOS's green, which the phone's switches use (systemGreen, dark). */
val SwitchGreen = Color(0xFF30D158)
private val SwitchOff = Color(0xFF8E8E93).copy(alpha = 0.33f)

/**
 * The phone's switch (hig's CupertinoSwitch): a 64 x 28 capsule whose white thumb, when
 * pressed or dragged, swells to one and a half times its size and turns to clear glass that
 * bends the track under it, then settles back to white. Drag it or click it.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val updatedChecked by rememberUpdatedState(checked)
    val updatedOnChange by rememberUpdatedState(onCheckedChange)
    val density = LocalDensity.current
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    updatedOnChange(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (updatedChecked) 0f else 1f
                    updatedOnChange(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                fraction = (fraction + dragAmount.x / dragWidth).fastCoerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(drag) {
        snapshotFlow { fraction }.collectLatest { drag.updateValue(it) }
    }
    LaunchedEffect(drag) {
        snapshotFlow { updatedChecked }.collectLatest { on ->
            val target = if (on) 1f else 0f
            if (target != fraction) {
                fraction = target
                drag.animateToValue(target)
            }
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val onTrack = if (enabled) SwitchGreen else SwitchGreen.copy(alpha = 0.33f)
    Box(modifier.then(if (enabled) Modifier else Modifier.alpha(0.5f)), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(ContinuousCapsule)
                .drawBehind { drawRect(lerp(SwitchOff, onTrack, drag.value)) }
                .size(64.dp, 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    translationX = lerp(padding, padding + dragWidth, drag.value)
                }
                .semantics { role = Role.Switch }
                .then(if (enabled) drag.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                        val progress = drag.pressProgress
                        scale(lerp(2f / 3f, 0.75f, progress), lerp(0f, 0.75f, progress)) { drawBackdrop() }
                    },
                    shape = { ContinuousCapsule },
                    effects = {
                        val progress = drag.pressProgress
                        // At rest the white thumb covers the backdrop entirely.
                        if (progress != 0f) {
                            blur(8.dp.toPx() * (1f - progress))
                            lens(5.dp.toPx() * progress, 10.dp.toPx() * progress, chromaticAberration = true)
                        }
                    },
                    onDrawBackdrop = { drawBackdrop -> if (drag.pressProgress != 0f) drawBackdrop() },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = drag.pressProgress,
                        )
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val progress = drag.pressProgress
                        InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = drag.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drag.pressProgress)) },
                )
                .size(40.dp, 24.dp),
        )
    }
}
