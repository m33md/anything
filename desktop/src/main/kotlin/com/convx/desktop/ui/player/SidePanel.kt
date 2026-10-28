/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.formatSeconds
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.EqualizerBars
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.panel
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberHeroColors
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.SidePanel
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlin.math.roundToInt

/** The right-hand panel: now playing, lyrics or the queue. */
@Composable
fun RightPanel(panel: SidePanel, modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val hero by rememberHeroColors(state.current?.thumbnail)
    val tint = hero?.background ?: Color(0xFF1C1C1E)
    Column(
        modifier
            .width(360.dp)
            .fillMaxHeight()
            .panel()
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.55f), 0.6f to tint.copy(alpha = 0.12f), 1f to Color.Transparent)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(ConvxShapes.Capsule).background(Color.Black.copy(alpha = 0.25f)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (p in SidePanel.entries) {
                    val selected = p == panel
                    Box(
                        Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(ConvxShapes.Capsule)
                            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                            .pressable({ UiState.openSidePanel(p) }, pressScale = 0.97f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (p) {
                                SidePanel.NowPlaying -> "Now Playing"
                                SidePanel.Lyrics -> "Lyrics"
                                SidePanel.Queue -> "Queue"
                            },
                            style = ConvxType.SubheadStrong,
                            color = if (selected) Color.White else ConvxColors.SecondaryLabel,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton("close", { UiState.closeSidePanel() }, size = 32.dp, iconSize = 18.dp, tooltip = "Close")
        }
        Crossfade(panel, modifier = Modifier.weight(1f)) { p ->
            when (p) {
                SidePanel.NowPlaying -> NowPlayingPanel(state)
                SidePanel.Lyrics -> LyricsView(
                    state.current,
                    Modifier.fillMaxSize(),
                    style = ConvxType.LyricsCompact,
                )
                SidePanel.Queue -> QueueList(state, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(state: PlayerState) {
    val song = state.current
    if (song == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Nothing playing yet.\nPick something to listen to.", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
        }
        return
    }
    val library by Library.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    val navigator = LocalNavigator.current
    val menu = rememberMenuAnchor()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Artwork(
            song.thumbnail,
            Modifier.fillMaxWidth().aspectRatio(1f).shadow(18.dp, RoundedCornerShape(12.dp)).pressable({ UiState.fullPlayer = true }, pressScale = 0.99f),
            size = 800,
            shape = RoundedCornerShape(12.dp),
            iconSize = 56.dp,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = ConvxType.Title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ArtistLinks(song, ConvxType.Body.copy(color = ConvxColors.SecondaryLabel))
            }
            IconButton(
                if (liked) "favorite" else "favorite_border",
                { Library.toggleLike(song) },
                tint = if (liked) LocalAccent.current else Color.White,
                tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
            )
            Box {
                IconButton("more_horiz", { menu.expanded = true }, tooltip = "More options")
                AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
            }
        }
        state.stream?.let { stream ->
            Spacer(Modifier.height(10.dp))
            QualityBadge(stream.badge, stream.detail, ConvxColors.SecondaryLabel)
        }
        song.album?.let { album ->
            Spacer(Modifier.height(18.dp))
            InfoCard("From the album") {
                Row(
                    Modifier.fillMaxWidth().pressable({ navigator.navigate(Screen.Album(album.id)) }, pressScale = 0.99f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConvxIcon("album", tint = ConvxColors.SecondaryLabel, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(album.name, style = ConvxType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ConvxIcon("chevron_right_px", tint = ConvxColors.SecondaryLabel, size = 20.dp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard("Next in queue", action = "Open queue", onAction = { UiState.openSidePanel(SidePanel.Queue) }) {
            val next = state.upNext.firstOrNull()
            if (next == null) {
                Text("Nothing up next", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            } else {
                QueueRow(next, state.index + 1, false, Modifier, removable = false)
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard("Lyrics", action = "Show", onAction = { UiState.openSidePanel(SidePanel.Lyrics) }) {
            Text("Synced, word by word when available.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoCard(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(ConvxShapes.Card).background(Color.White.copy(alpha = 0.07f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = ConvxType.Headline, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Text(
                    action,
                    style = ConvxType.SubheadStrong,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.pressable(onAction),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Now playing + Next up, with drag to reorder and hover to remove. */
@Composable
fun QueueList(state: PlayerState, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val listState = rememberLazyListState()
    val upNext = state.upNext
    val current = state.current
    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        if (current != null) {
            sectionTitle("Now playing")
            item(key = "current") { QueueRow(current, state.index, true, Modifier, removable = false) }
        }
        if (upNext.isNotEmpty()) {
            item(key = "next_header") {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Next up", style = ConvxType.Headline)
                        state.queueTitle?.let { Text("From $it", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    PillButton("Clear", { PlayerController.clearUpNext() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            itemsIndexed(upNext, key = { i, song -> "q${state.index + 1 + i}:${song.id}" }) { i, song ->
                QueueRow(song, state.index + 1 + i, false, Modifier, removable = true, draggable = true)
            }
        } else if (current == null) {
            item {
                Text(
                    "Your queue is empty.",
                    style = ConvxType.Body,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun LazyListScope.sectionTitle(text: String) {
    item(key = "title_$text") {
        Text(text, style = ConvxType.Headline, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp))
    }
}

@Composable
fun QueueRow(
    song: Song,
    position: Int,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    removable: Boolean,
    draggable: Boolean = false,
) {
    val playback = LocalPlayback.current
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 60.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 12f else 0f
            }
            .clip(ConvxShapes.Control)
            .background(
                when {
                    dragging -> Color(0xFF3A3A3C)
                    hovered || menu.expanded -> Color.White.copy(alpha = 0.07f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable({ if (isCurrent) PlayerController.togglePlay() else PlayerController.skipTo(position) }, interaction = interaction, pressScale = 0.99f),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp)) {
                Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 120, shape = RoundedCornerShape(6.dp), iconSize = 18.dp)
                if (isCurrent) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        EqualizerBars(Color.White, playback.isPlaying)
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                // White on the artwork-tinted panels; the equalizer on the cover already marks it.
                Text(song.title, style = if (isCurrent) ConvxType.BodyStrong else ConvxType.Body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistText, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hovered && removable) {
                IconButton("remove", { PlayerController.removeFromQueue(position) }, size = 30.dp, iconSize = 18.dp, tooltip = "Remove from queue")
            } else {
                Text(formatSeconds(song.duration), style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
            if (draggable) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(position) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    val steps = (dragOffset / rowHeightPx).roundToInt()
                                    if (steps != 0) PlayerController.moveInQueue(position, (position + steps).coerceAtLeast(0))
                                    dragOffset = 0f
                                    dragging = false
                                },
                                onDragCancel = {
                                    dragOffset = 0f
                                    dragging = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ConvxIcon("drag_handle", tint = ConvxColors.SecondaryLabel, size = 18.dp)
                }
            }
        }
        AppMenu(menu) {
            SongMenuContent(song, { menu.expanded = false }) { dismiss ->
                if (removable) {
                    MenuItem("Remove from queue", "remove") {
                        PlayerController.removeFromQueue(position)
                        dismiss()
                    }
                }
            }
        }
    }
}
