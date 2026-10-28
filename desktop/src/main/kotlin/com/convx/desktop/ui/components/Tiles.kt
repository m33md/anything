/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.theme.ConvxColors

/** A glyph on a gradient: the cover of a list with no artwork of its own (Liked Songs, History). */
@Composable
fun GradientTile(icon: String, size: Dp, colors: List<Color>, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(
        Modifier.size(size).clip(shape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = Color.White, size = size * 0.45f)
    }
}

/** A playlist's cover: its first four covers in a 2x2 grid, or the first one, or a note. */
@Composable
fun MosaicArt(thumbnails: List<String>, size: Dp, shape: Shape = RoundedCornerShape(6.dp)) {
    when {
        thumbnails.size >= 4 -> Column(Modifier.size(size).clip(shape)) {
            for (r in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (c in 0 until 2) {
                        Artwork(thumbnails[r * 2 + c], Modifier.weight(1f).fillMaxHeight(), size = 160, shape = RoundedCornerShape(0.dp))
                    }
                }
            }
        }
        thumbnails.isNotEmpty() -> Artwork(thumbnails.first(), Modifier.size(size), size = 300, shape = shape)
        else -> Box(
            Modifier.size(size).clip(shape).background(ConvxColors.CardTertiary),
            contentAlignment = Alignment.Center,
        ) { ConvxIcon("queue_music", tint = ConvxColors.SecondaryLabel, size = size * 0.42f) }
    }
}
