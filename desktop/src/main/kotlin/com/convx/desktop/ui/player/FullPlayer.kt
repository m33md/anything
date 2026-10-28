/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.MeshColors
import com.convx.desktop.player.PlayerController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val BlobAnchors = listOf(Offset(0.20f, 0.25f), Offset(0.80f, 0.20f), Offset(0.75f, 0.80f), Offset(0.25f, 0.75f))
private val BlobSpeeds = listOf(1f, -0.7f, 0.85f, -1.15f)
private const val DriftRadians = (PI * 0.45).toFloat()

/**
 * The phone's player backdrop: four colours from the cover as soft blobs on a dark
 * wash of the cover's own hue, blurred together. The blobs drift when the track
 * changes and rest otherwise, so a still screen costs nothing.
 */
@Composable
fun MeshBackground(colors: List<Color>?, trackKey: Any?, modifier: Modifier = Modifier, scrim: Float = 0.38f) {
    val tuned = (colors ?: MeshColors.Fallback).let { (it + MeshColors.Fallback).take(4) }
    val spec = tween<Color>(1400, easing = FastOutSlowInEasing)
    val animated = tuned.map { animateColorAsState(it, spec) }
    val base = animateColorAsState(MeshColors.dimmed(tuned.first()), spec)
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey) {
        phase.animateTo(phase.value + DriftRadians, tween(8_000, easing = FastOutSlowInEasing))
    }
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .blur(64.dp),
    ) {
        val drift = phase.value
        drawRect(base.value)
        animated.forEachIndexed { index, state ->
            val anchor = BlobAnchors[index]
            val speed = BlobSpeeds[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speed + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speed * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(listOf(state.value.copy(alpha = 0.85f), state.value.copy(alpha = 0f)), center, radius),
                radius = radius,
                center = center,
            )
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = scrim * 0.26f), Color.Black.copy(alpha = scrim))))
    }
}

/** Full-window player: artwork and controls on the left, lyrics or the queue on the right. */
@Composable
fun FullPlayer(modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val song = state.current
    val mesh by rememberMeshColors(song?.thumbnail?.let { com.convx.desktop.ui.components.artworkUrl(it, 226) })
    val mode = UiState.fullPlayerMode
    val menu = rememberMenuAnchor()

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow clicks so nothing underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        MeshBackground(mesh, song?.id)

        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            // Top row
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton("expand_more", { UiState.fullPlayer = false }, size = 40.dp, iconSize = 28.dp, filled = true, tooltip = "Close (Esc)")
                Spacer(Modifier.weight(1f))
                state.queueTitle?.let {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = ConvxType.Footnote, color = Color.White.copy(alpha = 0.6f))
                        Text(it, style = ConvxType.SubheadStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    if (mode == FullPlayerMode.Lyrics) "lyrics_active" else "lyrics",
                    { UiState.fullPlayerMode = if (mode == FullPlayerMode.Lyrics) FullPlayerMode.Artwork else FullPlayerMode.Lyrics },
                    size = 40.dp,
                    iconSize = 22.dp,
                    filled = mode == FullPlayerMode.Lyrics,
                    tooltip = "Lyrics",
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    "apple_queue",
                    { UiState.fullPlayerMode = if (mode == FullPlayerMode.Queue) FullPlayerMode.Artwork else FullPlayerMode.Queue },
                    size = 40.dp,
                    iconSize = 22.dp,
                    filled = mode == FullPlayerMode.Queue,
                    tooltip = "Queue",
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val split = mode != FullPlayerMode.Artwork
                val leftWidth = if (split) min(maxWidth.value * 0.42f, 560f).dp else min(maxWidth.value, 620f).dp
                val artSize = min(leftWidth.value - 20f, maxHeight.value - 300f).coerceAtLeast(160f).dp
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (split) Arrangement.Start else Arrangement.Center) {
                    Column(
                        Modifier.width(leftWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Apple's tell: the cover sinks back a little while paused.
                        val artScale by animateFloatAsState(
                            if (state.isPlaying || state.isLoading) 1f else 0.86f,
                            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
                        )
                        Box(Modifier.size(artSize), contentAlignment = Alignment.Center) {
                            Artwork(
                                song?.thumbnail,
                                Modifier
                                    .size(artSize)
                                    .graphicsLayer {
                                        scaleX = artScale
                                        scaleY = artScale
                                    }
                                    .shadow(if (state.isPlaying) 40.dp else 18.dp, RoundedCornerShape(14.dp)),
                                size = 1000,
                                shape = RoundedCornerShape(14.dp),
                                iconSize = 80.dp,
                            )
                        }
                        Spacer(Modifier.height(28.dp))
                        Column(Modifier.width(artSize.coerceAtLeast(360.dp))) {
                            if (song != null) {
                                val liked = library.liked.any { it.song.id == song.id }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        // The phone sets title and artist at one size, semibold over regular.
                                        val line = ConvxType.Headline.copy(fontSize = 21.sp, lineHeight = 27.sp)
                                        Text(song.title, style = line, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        ArtistLinks(song, line.copy(color = Color.White.copy(alpha = 0.6f), fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                                    }
                                    IconButton(
                                        if (liked) "favorite" else "favorite_border",
                                        { Library.toggleLike(song) },
                                        size = 38.dp,
                                        iconSize = 22.dp,
                                        filled = true,
                                        tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box {
                                        IconButton("more_horiz", { menu.expanded = true }, size = 38.dp, iconSize = 22.dp, filled = true, tooltip = "More options")
                                        AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            ProgressRow(state, settings.wavyProgress, labelColor = Color.White.copy(alpha = 0.6f), labelsBelow = true, showQuality = true)
                            Spacer(Modifier.height(8.dp))
                            TransportControls(state, compact = false, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                VolumeControl(state, sliderWidth = 220.dp)
                            }
                        }
                    }
                    if (split) {
                        Spacer(Modifier.width(40.dp))
                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { m ->
                            when (m) {
                                FullPlayerMode.Lyrics -> LyricsView(
                                    song,
                                    Modifier.fillMaxSize().widthIn(max = 900.dp),
                                    style = ConvxType.Lyrics,
                                    immersive = true,
                                    horizontalPadding = 8.dp,
                                    dimColor = Color.White.copy(alpha = 0.32f),
                                )
                                FullPlayerMode.Queue -> QueueList(state, Modifier.fillMaxSize().padding(vertical = 12.dp), onDark = true)
                                FullPlayerMode.Artwork -> Spacer(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}
