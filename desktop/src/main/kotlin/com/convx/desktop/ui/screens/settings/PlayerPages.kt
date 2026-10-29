/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.PlayerBackground
import com.convx.desktop.data.PlayerDesign
import com.convx.desktop.data.PlayerLook
import com.convx.desktop.data.SeekBarStyle
import com.convx.desktop.data.Settings
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.player.PlayerBackdrop
import com.convx.desktop.ui.player.PlayerStage
import com.convx.desktop.ui.player.SeekBar
import com.convx.desktop.ui.player.WaveformSeekBar
import com.convx.desktop.ui.player.heroLayout
import com.convx.desktop.ui.player.tiltedGradient
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlin.math.roundToInt

/**
 * The phone's Player design sheet, for a window: the five designs side by side, each a small copy
 * of the real player with the song that's playing (or the last one played), and under them the
 * options that belong to the design in use.
 */
@Composable
fun PlayerDesignSettings() {
    val settings by AppSettings.state.collectAsState()
    val playing by PlayerController.state.collectAsState()
    val library by Library.state.collectAsState()
    // Nothing playing: the last song played stands in, as on the phone.
    val state = remember(playing, library.history.firstOrNull()) {
        if (playing.current != null) playing
        else library.history.firstOrNull()?.song?.let { PlayerState(queue = listOf(it), index = 0) } ?: playing
    }
    val design = settings.playerDesign
    fun update(transform: (Settings) -> Settings) = AppSettings.update(transform)

    Text(
        state.current?.let { "Previewing “${it.title}”" } ?: "Play a song to preview it with its artwork",
        style = ConvxType.Subhead,
        color = ConvxColors.SecondaryLabel,
        modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
    )
    // A grid rather than a row: a mouse wheel doesn't scroll sideways, so every design stays in view.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlayerDesign.entries.forEach { d ->
            DesignChoice(d.label, selected = d == design, inUse = true, onClick = { update { it.copy(playerDesign = d) } }) {
                ScaledStage(200.dp, settings.copy(playerDesign = d), state)
            }
        }
    }

    if (design == PlayerDesign.AppleMusicV2) {
        SectionGap()
        SettingsSection("Layout") {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PlayerLook.entries.forEach { look ->
                    DesignChoice(look.label, selected = settings.playerLook == look, onClick = { update { it.copy(playerLook = look) } }) {
                        ScaledStage(200.dp, settings.copy(playerLook = look), state)
                    }
                }
            }
        }
    }

    // Classic designs and v2's square layout take a background; full bleed and Apple Music paint their own.
    if (design.classic || (design == PlayerDesign.AppleMusicV2 && !settings.heroLayout)) {
        SectionGap()
        SettingsSection(
            "Background",
            footer = if (design == PlayerDesign.AppleMusicV2) "Gradient and Apple are Apple Music's own: the cover's top and bottom edge colours." else null,
        ) {
            val styles = PlayerBackground.entries.filter { it != PlayerBackground.Apple || design == PlayerDesign.AppleMusicV2 }
            val current = settings.playerBackground.let { if (it == PlayerBackground.Apple && design != PlayerDesign.AppleMusicV2) PlayerBackground.Gradient else it }
            FlowRow(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                styles.forEach { style ->
                    PickTile(style.label, selected = current == style, onClick = { update { it.copy(playerBackground = style) } }) {
                        Box(Modifier.size(width = 128.dp, height = 80.dp).clipToBounds()) {
                            PlayerBackdrop(style, state.current?.thumbnail, playing = current == style && playing.isPlaying, settings = settings, hero = false)
                        }
                    }
                }
            }
            if (current == PlayerBackground.Solid || current == PlayerBackground.Custom) {
                RowDivider(PlainInset)
                ButtonRow(if (current == PlayerBackground.Solid) "Edit colour" else "Edit gradient") {
                    if (current == PlayerBackground.Solid) solidColourDialog() else gradientDialog()
                }
            }
        }
    }

    // Both Apple designs draw Apple's own progress capsule, so the styles only apply to the classic ones.
    if (design.classic) {
        SectionGap()
        SettingsSection("Seek bar") {
            FlowRow(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SeekBarStyle.entries.forEach { style ->
                    PickTile(style.label, selected = settings.seekBarStyle == style, onClick = { update { it.copy(seekBarStyle = style) } }) {
                        Box(Modifier.size(width = 160.dp, height = 64.dp).background(Color(0xFF1C1C1E)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            SeekBarSample(style, playing.isPlaying)
                        }
                    }
                }
            }
        }
    }

    SectionGap()
    SettingsSection {
        if (!settings.heroLayout) {
            SwitchRow("Artwork shadow", checked = settings.artworkShadow || design == PlayerDesign.AppleMusicV2, enabled = design != PlayerDesign.AppleMusicV2) { v ->
                update { it.copy(artworkShadow = v) }
            }
            RowDivider(PlainInset)
        }
        SwitchRow("Show next track", "What plays next, under the controls", settings.showUpNext) { v -> update { it.copy(showUpNext = v) } }
        RowDivider(PlainInset)
        SwitchRow("Volume bar", checked = settings.showVolumeBar) { v -> update { it.copy(showVolumeBar = v) } }
    }
}

/** The player at a window's size (1280 by 800), drawn into [width]: the phone's phone previews, for a PC. */
@Composable
private fun ScaledStage(width: Dp, settings: Settings, state: PlayerState) {
    Box(
        Modifier
            .clipToBounds()
            .layout { measurable, _ ->
                val w = 1280.dp.roundToPx()
                val h = 800.dp.roundToPx()
                val scale = width.toPx() / w
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout((w * scale).roundToInt(), (h * scale).roundToInt()) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            },
    ) {
        PlayerStage(state, settings, FullPlayerMode.Artwork, preview = true)
    }
}

/** A choice drawn as itself: a ring when it's chosen, its name under it. The cover over it takes the click. */
@Composable
private fun DesignChoice(label: String, selected: Boolean, inUse: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val ring by animateColorAsState(if (selected) SystemColors.Red else Color.Transparent, tween(180))
    val lift by animateFloatAsState(if (selected) 1f else 0.97f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) {
        Box(Modifier.border(2.5.dp, ring, RoundedCornerShape(16.dp)).padding(5.dp).clip(RoundedCornerShape(11.dp))) {
            content()
            // Over the copy of the player, so its buttons don't react and the whole tile picks.
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = ConvxType.Subhead,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else ConvxColors.SecondaryLabel,
            )
            if (inUse && selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "In use",
                    style = ConvxType.CaptionStrong,
                    color = SystemColors.Green,
                    modifier = Modifier.clip(CircleShape).background(SystemColors.Green.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PickTile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val ring by animateColorAsState(if (selected) SystemColors.Red else Color.Transparent, tween(180))
    val lift by animateFloatAsState(if (selected) 1f else 0.96f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(Modifier.border(2.5.dp, ring, RoundedCornerShape(16.dp)).padding(4.dp).clip(RoundedCornerShape(12.dp))) { content() }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = ConvxType.Caption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else ConvxColors.SecondaryLabel,
            maxLines = 1,
        )
    }
}

/** The seek bar the player draws for [style], held at 42%. */
@Composable
private fun SeekBarSample(style: SeekBarStyle, playing: Boolean) {
    val position = { 42_000L }
    when (style) {
        SeekBarStyle.Waveform -> WaveformSeekBar(position, 100_000L, {}, seed = 7)
        else -> SeekBar(
            positionMs = position,
            durationMs = 100_000L,
            onSeek = {},
            wavy = style == SeekBarStyle.Wavy,
            playing = playing || style == SeekBarStyle.Wavy,
            strokeWidth = if (style == SeekBarStyle.Slim) 3.dp else 4.dp,
            thumb = style == SeekBarStyle.Default,
        )
    }
}

// ---- colours ---------------------------------------------------------------------------------

private val Swatches = listOf(
    0xFF1A1A1A, 0xFF000000, 0xFF2C2C2E, 0xFF3A1C71, 0xFF1E3A5F, 0xFF0B4F3F,
    0xFF5B1A1A, 0xFF7A3E00, 0xFF4A154B, 0xFF0F2027, 0xFF203A43, 0xFF2C5364,
)

/** The Solid background's colour: a few to pick from, or any colour by hue, saturation and brightness. */
private fun solidColourDialog() {
    Overlays.showDialog {
        val settings by AppSettings.state.collectAsState()
        DialogCard("Solid colour") {
            ColourEditor(Color(settings.playerSolidColor)) { c -> AppSettings.update { it.copy(playerSolidColor = c.toArgbLong()) } }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Done", { Overlays.dismissDialog() }, filled = true)
            }
        }
    }
}

/** The Custom background: its colours, start and end, and the angle it runs at. */
private fun gradientDialog() {
    Overlays.showDialog {
        val settings by AppSettings.state.collectAsState()
        var editing by remember { mutableIntStateOf(0) }
        val stops = settings.playerGradientStops.ifEmpty { listOf(0xFF3A1C71, 0xFFD76D77) }.let { if (it.size == 1) it + it else it }
        DialogCard("Custom gradient") {
            Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).tiltedGradient(stops.map { Color(it) }, settings.playerGradientAngle))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stops.forEachIndexed { i, stop ->
                    val chosen = i == editing
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (chosen) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { editing = i }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(stop)))
                        Spacer(Modifier.width(6.dp))
                        Text(if (i == 0) "Start" else "End", style = ConvxType.Subhead, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ColourEditor(Color(stops[editing.coerceIn(stops.indices)])) { c ->
                AppSettings.update { s ->
                    val now = s.playerGradientStops.ifEmpty { listOf(0xFF3A1C71, 0xFFD76D77) }.let { if (it.size == 1) it + it else it }.toMutableList()
                    now[editing.coerceIn(now.indices)] = c.toArgbLong()
                    s.copy(playerGradientStops = now)
                }
            }
            Spacer(Modifier.height(6.dp))
            SliderRow("Angle", "${settings.playerGradientAngle.roundToInt()}°", settings.playerGradientAngle, 0f..360f) { v ->
                AppSettings.update { it.copy(playerGradientAngle = (v / 15f).roundToInt() * 15f) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Done", { Overlays.dismissDialog() }, filled = true)
            }
        }
    }
}

/** Swatches, then hue, saturation and brightness for anything else. */
@Composable
private fun ColourEditor(colour: Color, onChange: (Color) -> Unit) {
    val hsv = remember(colour) {
        FloatArray(3).also { com.convx.desktop.ui.theme.rgbToHsv(colour.red, colour.green, colour.blue, it) }
    }
    var hue by remember(colour) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(colour) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(colour) { mutableFloatStateOf(hsv[2]) }
    fun emit() = onChange(Color.hsv(hue.coerceIn(0f, 359.9f), saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)))
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatches.take(6).forEach { SwatchDot(Color(it), Color(it).toArgb() == colour.toArgb()) { onChange(Color(it)) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatches.drop(6).forEach { SwatchDot(Color(it), Color(it).toArgb() == colour.toArgb()) { onChange(Color(it)) } }
        }
        Spacer(Modifier.height(6.dp))
        SliderRow("Hue", "${hue.roundToInt()}°", hue, 0f..359f) { hue = it; emit() }
        SliderRow("Saturation", "${(saturation * 100).roundToInt()}%", saturation, 0f..1f) { saturation = it; emit() }
        SliderRow("Brightness", "${(brightness * 100).roundToInt()}%", brightness, 0f..1f) { brightness = it; emit() }
    }
}

@Composable
private fun SwatchDot(colour: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.15f), CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(colour)
            .clickable(onClick = onClick),
    )
}

private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
