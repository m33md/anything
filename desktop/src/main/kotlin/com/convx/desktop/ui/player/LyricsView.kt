/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Song
import com.convx.desktop.lyrics.Lyrics
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.music.lyrics.bitchord.LyricAlignment
import com.convx.music.lyrics.bitchord.LyricLine
import kotlin.math.abs

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object Missing : LyricsLoad
    data class Ready(val lyrics: Lyrics) : LyricsLoad
}

/** Lines light up a touch before their stamp, the way Apple Music leads the eye. */
private const val LeadMs = 120L

/**
 * Apple Music style lyrics: big bold lines, the current one lit and swept word by
 * word, the rest dimmed and (in [immersive] mode) softly blurred by distance.
 * Click a line to jump there; scrolling by hand pauses the follow for a moment.
 */
@Composable
fun LyricsView(
    song: Song?,
    modifier: Modifier = Modifier,
    style: TextStyle = ConvxType.Lyrics,
    immersive: Boolean = true,
    horizontalPadding: Dp = 32.dp,
    dimColor: Color = Color.White.copy(alpha = 0.3f),
    litColor: Color = Color.White,
) {
    val load by produceState<LyricsLoad>(LyricsLoad.Loading, song?.id) {
        if (song == null) {
            value = LyricsLoad.Missing
            return@produceState
        }
        val cached = LyricsService.cached(song.id)
        value = if (cached != null) LyricsLoad.Ready(cached) else LyricsLoad.Loading
        val lyrics = LyricsService.get(song)
        value = if (lyrics == null || lyrics.lines.isEmpty()) LyricsLoad.Missing else LyricsLoad.Ready(lyrics)
    }
    Box(modifier) {
        when (val l = load) {
            LyricsLoad.Loading -> LoadingState()
            LyricsLoad.Missing -> Column(
                Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No lyrics for this song", style = style.copy(fontSize = style.fontSize * 0.7f), color = dimColor)
            }
            is LyricsLoad.Ready -> if (l.lyrics.synced) {
                SyncedLyrics(l.lyrics, style, immersive, horizontalPadding, dimColor, litColor)
            } else {
                PlainLyrics(l.lyrics, style, horizontalPadding, dimColor)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    style: TextStyle,
    immersive: Boolean,
    horizontalPadding: Dp,
    dimColor: Color,
    litColor: Color,
) {
    val position = rememberSmoothPosition()
    val wordSync = AppSettings.state.collectAsState().value.lyricsWordSync
    val lines = lyrics.lines
    val active by remember(lines) {
        derivedStateOf {
            val now = position.value + LeadMs
            var index = -1
            for (i in lines.indices) {
                if (lines[i].timeMs <= now) index = i else break
            }
            index
        }
    }
    val listState = rememberLazyListState()
    var userScrollUntil by remember { mutableLongStateOf(0L) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPad = maxHeight * 0.28f
        LaunchedEffect(active) {
            if (System.currentTimeMillis() < userScrollUntil) return@LaunchedEffect
            if (active >= 0) listState.animateScrollToItem(active)
            else listState.animateScrollToItem(0)
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = topPad, bottom = maxHeight * 0.6f),
            modifier = Modifier
                .fillMaxSize()
                // Lines fade out at the edges instead of being cut by them.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.84f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .hoverable(interaction)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) userScrollUntil = System.currentTimeMillis() + 3500
                        }
                    }
                },
        ) {
            itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                val distance = if (active < 0) index + 1 else abs(index - active)
                LyricLineItem(
                    line = line,
                    isActive = index == active,
                    distance = distance,
                    blurred = immersive && !hovered,
                    position = position,
                    style = style,
                    dimColor = dimColor,
                    litColor = litColor,
                    wordSync = wordSync,
                    onClick = {
                        userScrollUntil = 0L
                        PlayerController.seekTo(line.timeMs)
                        PlayerController.play()
                    },
                )
            }
            item(key = "credit") {
                Text(
                    "Lyrics from ${lyrics.source}",
                    style = ConvxType.Caption,
                    color = ConvxColors.TertiaryLabel,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    distance: Int,
    blurred: Boolean,
    position: State<Long>,
    style: TextStyle,
    dimColor: Color,
    litColor: Color,
    wordSync: Boolean,
    onClick: () -> Unit,
) {
    val blur by animateDpAsState(if (blurred && !isActive) (distance.coerceAtMost(4) * 1.4f).dp else 0.dp, tween(500))
    val scale by animateFloatAsState(if (isActive) 1f else 0.96f, tween(450))
    val align = if (line.alignment == LyricAlignment.End) TextAlign.End else TextAlign.Start
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = (style.fontSize.value * 0.32f).dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.99f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (align == TextAlign.End) 1f else 0f, 0.5f)
                }
                .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier),
            horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start,
        ) {
            if (line.isGap) {
                InterludeDots(isActive, style, dimColor, litColor)
            } else {
                SweptText(line, isActive, position, style, align, dimColor, litColor, wordSync)
                line.background?.let { bg ->
                    SweptText(
                        bg,
                        isActive,
                        position,
                        style.copy(fontSize = style.fontSize * 0.62f, lineHeight = style.lineHeight * 0.66f),
                        align,
                        dimColor,
                        litColor.copy(alpha = 0.85f),
                        wordSync,
                    )
                }
            }
        }
    }
}

/**
 * One line of text. Inactive: dim. Active without word timing: lit. Active with word
 * timing: dim underneath with the lit copy wiped in behind the sung characters, the
 * wipe edge feathered. The wipe reads [position] at draw time, so it costs no
 * recompositions.
 */
@Composable
private fun SweptText(
    line: LyricLine,
    isActive: Boolean,
    position: State<Long>,
    style: TextStyle,
    align: TextAlign,
    dimColor: Color,
    litColor: Color,
    wordSync: Boolean,
) {
    val swept = wordSync && line.isWordSynced
    val color by animateColorAsState(if (isActive && !swept) litColor else dimColor, tween(350))
    if (!isActive || !swept) {
        Text(line.text, style = style, color = color, textAlign = align)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        Text(line.text, style = style, color = dimColor, textAlign = align, onTextLayout = { layout = it })
        Text(
            line.text,
            style = style,
            color = litColor,
            textAlign = align,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val result = layout ?: return@drawWithContent
                    val sung = sungChars(line, position.value + LeadMs / 2)
                    val feather = 18.dp.toPx()
                    for (l in 0 until result.lineCount) {
                        val top = result.getLineTop(l)
                        val height = result.getLineBottom(l) - top
                        val start = result.getLineStart(l)
                        val end = result.getLineEnd(l)
                        when {
                            sung >= end -> Unit
                            sung <= start -> drawRect(Color.Black, Offset(0f, top), Size(size.width, height), blendMode = BlendMode.DstOut)
                            else -> {
                                val i = sung.toInt().coerceIn(start, end - 1)
                                val x0 = result.getHorizontalPosition(i, true)
                                val x1 = if (i + 1 < end) result.getHorizontalPosition(i + 1, true) else result.getLineRight(l)
                                val x = x0 + (x1 - x0) * (sung - i)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        1f to Color.Black,
                                        startX = x - feather / 2,
                                        endX = x + feather / 2,
                                    ),
                                    topLeft = Offset(0f, top),
                                    size = Size(size.width, height),
                                    blendMode = BlendMode.DstOut,
                                )
                            }
                        }
                    }
                },
        )
    }
}

/** How many characters of [line]'s text have been sung at [positionMs]. */
private fun sungChars(line: LyricLine, positionMs: Long): Float {
    val spans = line.wordSpans
    var sung = 0f
    for ((index, word) in line.words.withIndex()) {
        val span = spans.getOrNull(index) ?: break
        when {
            positionMs >= word.endMs -> sung = (span.last + 1).toFloat()
            positionMs > word.startMs -> return span.first + word.charsSungAt(positionMs)
            else -> return sung
        }
    }
    // Every word sung: light the trailing punctuation too.
    return if (line.words.isNotEmpty() && positionMs >= line.words.last().endMs) line.text.length.toFloat() else sung
}

@Composable
private fun InterludeDots(active: Boolean, style: TextStyle, dimColor: Color, litColor: Color) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    val dot = (style.fontSize.value * 0.32f).dp
    Row(
        Modifier.padding(vertical = 6.dp).graphicsLayer {
            val s = if (active) pulse else 1f
            scaleX = s
            scaleY = s
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        },
        horizontalArrangement = Arrangement.spacedBy(dot * 0.8f),
    ) {
        repeat(3) {
            Box(Modifier.size(dot).clip(CircleShape).background(if (active) litColor.copy(alpha = 0.9f) else dimColor))
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, style: TextStyle, horizontalPadding: Dp, dimColor: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "These lyrics aren't synced to the song.",
            style = ConvxType.Subhead,
            color = ConvxColors.SecondaryLabel,
        )
        lyrics.lines.forEach { line ->
            Text(line.text, style = style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.82f), color = Color.White.copy(alpha = 0.85f))
        }
        Text("Lyrics from ${lyrics.source}", style = ConvxType.Caption, color = ConvxColors.TertiaryLabel)
    }
}
