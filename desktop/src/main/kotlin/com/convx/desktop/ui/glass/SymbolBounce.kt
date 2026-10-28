/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The SF Symbols bounce the phone plays on a tab glyph as its tab lands and on glyphs you
 * tap (never on transport controls): a quick dip, then a loose spring that overshoots and
 * settles. Put [modifier] on the glyph and call [play].
 */
class SymbolBounce(private val scope: CoroutineScope) {
    private val scale = Animatable(1f)

    val modifier: Modifier = Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }

    fun play() {
        scope.launch {
            scale.animateTo(0.8f, tween(90, easing = FastOutLinearInEasing))
            scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f))
        }
    }
}

@Composable
fun rememberSymbolBounce(): SymbolBounce {
    val scope = rememberCoroutineScope()
    return remember(scope) { SymbolBounce(scope) }
}

/** Bounces when [selected] turns true, as a tab glyph does when its tab lands; not on first show. */
@Composable
fun SymbolBounce.onSelected(selected: Boolean): SymbolBounce {
    var was by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !was) play()
        was = selected
    }
    return this
}
