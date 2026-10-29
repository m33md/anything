/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings

// The phone's Grid & cards choices, as its sliders step through them. 0 is Auto.
val GridColumnChoices = listOf(0, 2, 3, 4, 5, 6)
val GridSpacingChoices = listOf(4, 8, 12, 16, 20, 24, 32)
val GridCardHeightChoices = listOf(0, 96, 112, 128, 144, 164, 184, 200, 220)
val HomeHeroHeightChoices = listOf(0, 200, 240, 280, 320, 360, 400, 440, 480)
val HomeCardCornerChoices = listOf(0, 8, 12, 16, 20, 24, 28, 36)

/** A card's 10dp frame either side of its cover, which lights up on hover. */
private val CardFrame = 20.dp

/**
 * How wide a card is: its cover, from the phone's card height or its Big and Small cell sizes
 * (164 and 128 there; 156 and 124 here, where Big was already 156), and the frame round it.
 */
fun Settings.cardWidth(): Dp = when {
    gridCardHeight > 0 -> gridCardHeight.dp
    gridSmall -> 124.dp
    else -> 156.dp
} + CardFrame

/** The width cards take where nothing sets one: the grid's, or the settings' size. */
val LocalCardWidth = compositionLocalOf { 176.dp }

/**
 * A page of cards, as the phone's grids lay them out: as many as fit at the chosen size, or the
 * chosen number of columns sharing the width, with the chosen gap between covers.
 */
@Composable
fun CardGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    // Each card's frame already keeps covers 20dp apart, so the gap is what's left of the choice:
    // the phone's default 16 keeps the PC's grid as it was, and it can't go tighter than the frames.
    val across = (settings.gridSpacing - 12).coerceAtLeast(0).dp
    val down = (settings.gridSpacing - 8).coerceAtLeast(0).dp
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp)) {
        val columns = settings.gridColumns
        // Half a dp under the exact share, so rounding never pushes the last card onto a new row.
        val width = if (columns > 0) ((maxWidth - across * (columns - 1)) / columns - 0.5.dp).coerceAtLeast(60.dp) else LocalCardWidth.current
        CompositionLocalProvider(LocalCardWidth provides width) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(across),
                verticalArrangement = Arrangement.spacedBy(down),
            ) {
                content()
            }
        }
    }
}
