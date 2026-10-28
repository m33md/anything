/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.formatDuration
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.LinkText
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.SidePanel
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** Spotify's bottom bar, in the phone's clothes: song on the left, transport in the middle, extras on the right. */
@Composable
fun PlayerBar(modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val panel = SidePanel.of(settings.rightPanel)
    Row(
        modifier.fillMaxWidth().height(84.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(0.3f)) {
            state.current?.let { NowPlayingInfo(it) }
        }
        Column(
            Modifier.weight(0.4f).widthIn(max = 760.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TransportControls(state, compact = true)
            Spacer(Modifier.height(2.dp))
            ProgressRow(state, wavy = settings.wavyProgress)
        }
        Row(
            Modifier.weight(0.3f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                if (panel == SidePanel.Lyrics) "lyrics_active" else "lyrics",
                { UiState.toggleSidePanel(SidePanel.Lyrics) },
                size = 34.dp,
                iconSize = 19.dp,
                active = panel == SidePanel.Lyrics,
                activeTint = LocalAccent.current,
                tooltip = "Lyrics",
            )
            IconButton(
                "apple_queue",
                { UiState.toggleSidePanel(SidePanel.Queue) },
                size = 34.dp,
                iconSize = 19.dp,
                active = panel == SidePanel.Queue,
                activeTint = LocalAccent.current,
                tooltip = "Queue",
            )
            IconButton(
                "sidebar",
                { UiState.toggleSidePanel(SidePanel.NowPlaying) },
                modifier = Modifier.graphicsLayer { scaleX = -1f },
                size = 34.dp,
                iconSize = 19.dp,
                active = panel == SidePanel.NowPlaying,
                activeTint = LocalAccent.current,
                tooltip = "Now playing view",
            )
            Spacer(Modifier.width(4.dp))
            VolumeControl(state)
            Spacer(Modifier.width(4.dp))
            IconButton(
                "fullscreen",
                { UiState.fullPlayer = true },
                size = 34.dp,
                iconSize = 19.dp,
                enabled = state.current != null,
                tooltip = "Full screen (F11)",
            )
        }
    }
}

@Composable
private fun NowPlayingInfo(song: Song) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    val menu = rememberMenuAnchor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.onSecondaryClick(menu.openAtProvider())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(58.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .hoverable(interaction)
                    .pressable({ UiState.fullPlayer = true }, interaction = interaction),
            ) {
                Artwork(song.thumbnail, Modifier.size(58.dp), size = 200, iconSize = 22.dp)
                if (hovered) {
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ConvxIcon("expand_less", tint = Color.White, size = 26.dp)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                val album = song.album
                if (album != null) {
                    LinkText(song.title, ConvxType.BodyStrong.copy(color = Color.White)) { navigator.navigate(Screen.Album(album.id)) }
                } else {
                    Text(song.title, style = ConvxType.BodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ArtistLinks(song, ConvxType.Subhead.copy(color = ConvxColors.SecondaryLabel))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                if (liked) "favorite" else "favorite_border",
                { Library.toggleLike(song) },
                size = 34.dp,
                iconSize = 19.dp,
                tint = if (liked) LocalAccent.current else ConvxColors.SecondaryLabel,
                tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
            )
        }
        AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
    }
}

/** Shuffle, previous, play/pause, next, repeat. */
@Composable
fun TransportControls(
    state: PlayerState,
    compact: Boolean,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val small = if (compact) 34.dp else 48.dp
    val smallIcon = if (compact) 18.dp else 24.dp
    val skip = if (compact) 38.dp else 60.dp
    val skipIcon = if (compact) 24.dp else 38.dp
    val play = if (compact) 38.dp else 72.dp
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 22.dp),
    ) {
        IconButton(
            if (state.shuffle) "shuffle_on" else "shuffle",
            { PlayerController.toggleShuffle() },
            size = small,
            iconSize = smallIcon,
            tint = tint.copy(alpha = 0.75f),
            active = state.shuffle,
            activeTint = accent,
            tooltip = if (state.shuffle) "Disable shuffle" else "Enable shuffle",
        )
        IconButton("apple_skip_previous", { PlayerController.previous() }, size = skip, iconSize = skipIcon, tint = tint, enabled = state.current != null, tooltip = "Previous")
        if (compact) {
            PlayButton(
                isPlaying = state.isPlaying,
                onClick = { PlayerController.togglePlay() },
                size = play,
                loading = state.isLoading,
            )
        } else {
            // The phone's full player: a bare glyph, no disc.
            IconButton(
                if (state.isPlaying) "pause_applemusic" else "play_applemusic",
                { PlayerController.togglePlay() },
                size = play,
                iconSize = 52.dp,
                tint = tint,
                enabled = state.current != null,
            )
        }
        IconButton("apple_skip_next", { PlayerController.next() }, size = skip, iconSize = skipIcon, tint = tint, enabled = state.current != null, tooltip = "Next")
        IconButton(
            when (state.repeat) {
                RepeatMode.ONE -> "repeat_one_on"
                RepeatMode.ALL -> "repeat_on"
                RepeatMode.OFF -> "repeat"
            },
            { PlayerController.cycleRepeat() },
            size = small,
            iconSize = smallIcon,
            tint = tint.copy(alpha = 0.75f),
            active = state.repeat != RepeatMode.OFF,
            activeTint = accent,
            tooltip = when (state.repeat) {
                RepeatMode.OFF -> "Enable repeat"
                RepeatMode.ALL -> "Enable repeat one"
                RepeatMode.ONE -> "Disable repeat"
            },
        )
    }
}

/** Elapsed · bar · total. Reads the position itself so the rest of the bar doesn't recompose 60 times a second. */
@Composable
fun ProgressRow(
    state: PlayerState,
    wavy: Boolean,
    modifier: Modifier = Modifier,
    labelColor: Color = ConvxColors.SecondaryLabel,
    labelsBelow: Boolean = false,
    showQuality: Boolean = false,
) {
    val position = rememberSmoothPosition()
    val seconds by remember { derivedStateOf { position.value / 1000 } }
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.duration ?: 0) * 1000L)
    val elapsed = formatDuration(seconds * 1000)
    val bar = @Composable { m: Modifier ->
        SeekBar(
            positionMs = { position.value },
            durationMs = duration,
            onSeek = { PlayerController.seekTo(it) },
            wavy = wavy,
            playing = state.isPlaying,
            hoverColor = LocalAccent.current,
            modifier = m,
        )
    }
    if (labelsBelow) {
        Column(modifier.fillMaxWidth()) {
            bar(Modifier.fillMaxWidth())
            // The phone keeps the quality badge centred between the two times.
            Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(elapsed, style = ConvxType.Caption, color = labelColor, modifier = Modifier.align(Alignment.CenterStart))
                if (showQuality) state.stream?.let { QualityBadge(it.badge, it.detail, labelColor, Modifier.align(Alignment.Center)) }
                Text(
                    "-" + formatDuration((duration - seconds * 1000).coerceAtLeast(0)),
                    style = ConvxType.Caption,
                    color = labelColor,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, style = ConvxType.Caption, color = labelColor, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Spacer(Modifier.width(8.dp))
            bar(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(duration), style = ConvxType.Caption, color = labelColor, modifier = Modifier.width(44.dp))
        }
    }
}

/** The phone's AudioQualityBadge: 11sp on a 15% wash with 5dp corners; hover shows the detail. */
@Composable
fun QualityBadge(label: String, detail: String, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        Tooltip(detail) {
            Text(
                label,
                style = ConvxType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = tint,
                maxLines = 1,
                modifier = Modifier
                    .shadow(2.dp, RoundedCornerShape(5.dp), ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.18f))
                    .clip(RoundedCornerShape(5.dp))
                    .background(tint.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun VolumeControl(state: PlayerState, sliderWidth: androidx.compose.ui.unit.Dp = 96.dp, tint: Color = Color.White) {
    val level = if (state.muted) 0f else state.volume
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            when {
                level <= 0f -> "volume_off"
                level < 0.5f -> "volume_down"
                else -> "volume_up"
            },
            { PlayerController.toggleMute() },
            size = 34.dp,
            iconSize = 19.dp,
            tint = tint,
            tooltip = if (state.muted) "Unmute" else "Mute",
        )
        ValueSlider(
            value = level,
            onValueChange = { PlayerController.setVolume(it) },
            hoverColor = LocalAccent.current,
            modifier = Modifier.width(sliderWidth),
        )
    }
}
