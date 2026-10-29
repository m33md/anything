/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.audio.EqBand
import com.convx.desktop.audio.EqPreset
import com.convx.desktop.audio.Equalizer
import com.convx.desktop.audio.EqualizerSettings
import com.convx.desktop.audio.ParametricEq
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.glass.GlassSlider
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private fun updateEq(transform: (EqualizerSettings) -> EqualizerSettings) =
    AppSettings.update { it.copy(equalizer = transform(it.equalizer)) }

/**
 * The phone's Convx Equalizer: import, the switches, then Simple (a three-way dial and the
 * presets) or Advanced (every band, the preamp and saved tunings).
 */
@Composable
fun EqualizerPage() {
    val settings by AppSettings.state.collectAsState()
    val eq = settings.equalizer
    // Set when the sound was tuned by hand rather than picked, so it can be saved.
    var dirty by rememberSaveable { mutableStateOf(false) }

    SettingsSection {
        SettingsRow(
            "Import Profile",
            "Choose a ParametricEQ .txt preset",
            icon = "equalizer",
            tint = SystemColors.Green,
            onClick = { importProfile() },
        )
    }
    SectionGap()
    SettingsSection {
        SwitchRow("Convx Equalizer", "Toggle professional audio processing", eq.enabled, icon = "equalizer", tint = SystemColors.Green) { v ->
            updateEq { it.copy(enabled = v) }
        }
        RowDivider()
        SwitchRow(
            "Spatial audio",
            "Widens stereo tracks for a more immersive, out-of-the-head feel on headphones",
            eq.spatialAudio,
            icon = "spatial_tracking_apple",
            tint = SystemColors.Blue,
        ) { v -> updateEq { it.copy(spatialAudio = v) } }
    }
    SectionGap()
    SegmentedControl(listOf("Simple", "Advanced"), if (eq.advanced) 1 else 0, Modifier.padding(horizontal = 8.dp)) { i ->
        updateEq { it.copy(advanced = i == 1) }
    }
    Spacer(Modifier.height(20.dp))
    AnimatedContent(
        targetState = eq.advanced,
        transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
    ) { advanced ->
        if (advanced) {
            AdvancedEq(eq, onTuned = { dirty = true }, onSaved = { dirty = false })
        } else {
            SimpleEq(eq, dirty, onTuned = { dirty = true }, onPicked = { dirty = false })
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SimpleEq(eq: EqualizerSettings, dirty: Boolean, onTuned: () -> Unit, onPicked: () -> Unit) {
    val gains = Equalizer.standardGains(eq.bands)
    val (bass, mid, treble) = gains?.let(Equalizer::dialOf) ?: Triple(0.0, 0.0, 0.0)
    fun dial(b: Double, m: Double, t: Double) {
        val (bands, preamp) = Equalizer.fromGains(Equalizer.triangle(b, m, t))
        updateEq { it.copy(bands = bands, preamp = preamp) }
        onTuned()
    }
    fun pick(values: List<Double>) {
        val (bands, preamp) = Equalizer.fromGains(values)
        updateEq { it.copy(bands = bands, preamp = preamp) }
        onPicked()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EqDial(
            bass.toFloat(), mid.toFloat(), treble.toFloat(),
            enabled = eq.enabled,
            onBass = { dial(it.toDouble(), mid, treble) },
            onMid = { dial(bass, it.toDouble(), treble) },
            onTreble = { dial(bass, mid, it.toDouble()) },
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(0.9f).aspectRatio(1f),
        )
        AnimatedVisibility(dirty && eq.enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            PillButton("Save", { askToSave(eq, onPicked) }, icon = "check")
        }
        if (eq.presets.isNotEmpty()) {
            PresetGroup(
                "Custom",
                eq.presets.map { it.name },
                isSelected = { i -> eq.presets[i].bands == eq.bands && eq.presets[i].preamp == eq.preamp },
                enabled = eq.enabled,
                onEdit = { managePresets(eq.presets) },
            ) { i ->
                val preset = eq.presets[i]
                updateEq { it.copy(bands = preset.bands, preamp = preset.preamp) }
                onPicked()
            }
        }
        Equalizer.Groups.forEach { group ->
            group.presets.chunked(4).forEachIndexed { row, chunk ->
                PresetGroup(
                    if (row == 0) group.title else null,
                    chunk.map { it.name },
                    isSelected = { i -> Equalizer.matches(gains, chunk[i].gains) },
                    enabled = eq.enabled,
                ) { i -> pick(chunk[i].gains) }
            }
        }
        InfoNote("Dolby Atmos and Dirac Audio here are the phone's equalizer curves with those names, not Dolby's or Dirac's own processing.")
    }
}

@Composable
private fun PresetGroup(
    title: String?,
    names: List<String>,
    isSelected: (Int) -> Boolean,
    enabled: Boolean,
    onEdit: (() -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = ConvxType.SubheadStrong,
                    color = LocalAccent.current,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, top = 4.dp),
                )
                if (onEdit != null && enabled) {
                    Box(
                        Modifier.size(28.dp).clip(ConvxShapes.Capsule).pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onEdit),
                        contentAlignment = Alignment.Center,
                    ) {
                        ConvxIcon("edit", tint = LocalAccent.current, size = 16.dp)
                    }
                }
            }
        }
        SegmentedButtons(names, isSelected, enabled = enabled, onClick = onPick)
    }
}

/**
 * The phone's CircularEqControl: Mids at the top, Bass and Treble below, the tuning drawn as a
 * soft blob between them. Grab near an axis and drag along it.
 */
@Composable
private fun EqDial(
    bass: Float,
    mid: Float,
    treble: Float,
    enabled: Boolean,
    onBass: (Float) -> Unit,
    onMid: (Float) -> Unit,
    onTreble: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val accent = LocalAccent.current
    val outline = Color(0xFF8E8E93)
    val surface = ConvxColors.Card
    val labelStyle = TextStyle(fontSize = 11.sp, color = Color.White)
    val valueStyle = TextStyle(fontSize = 13.sp, color = Color.White)
    var active by remember { mutableIntStateOf(-1) }
    val bassChange by rememberUpdatedState(onBass)
    val midChange by rememberUpdatedState(onMid)
    val trebleChange by rememberUpdatedState(onTreble)
    val angles = remember { doubleArrayOf(-PI / 2, -PI / 2 + 2 * PI / 3, -PI / 2 + 4 * PI / 3) }

    fun valueOn(axis: Int, pos: Offset, w: Float, h: Float): Float {
        val baseR = w / 2f * 0.35f
        val dot = (pos.x - w / 2f) * cos(angles[axis]).toFloat() + (pos.y - h / 2f) * sin(angles[axis]).toFloat()
        return ((dot / baseR - 1f) / 0.8f * 10f).coerceIn(-10f, 10f)
    }

    fun nearestAxis(pos: Offset, w: Float, h: Float): Int {
        val touch = atan2((pos.y - h / 2f).toDouble(), (pos.x - w / 2f).toDouble())
        val best = (0..2).minBy { i -> abs(atan2(sin(touch - angles[i]), cos(touch - angles[i]))) }
        return if (hypot((pos.x - w / 2f).toDouble(), (pos.y - h / 2f).toDouble()) < w * 0.48f) best else -1
    }

    fun send(axis: Int, value: Float) = when (axis) {
        0 -> midChange(value)
        1 -> bassChange(value)
        else -> trebleChange(value)
    }

    Canvas(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val axis = nearestAxis(down.position, w, h)
                    if (axis < 0) return@awaitEachGesture
                    down.consume()
                    active = axis
                    send(axis, valueOn(axis, down.position, w, h))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        send(axis, valueOn(axis, change.position, w, h))
                    }
                    active = -1
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.width / 2f * 0.9f
        val baseR = size.width / 2f * 0.35f
        val center = Offset(cx, cy)

        drawCircle(surface, outerR, center)
        drawCircle(outline.copy(alpha = 0.25f), outerR, center, style = Stroke(1.5f))

        val values = floatArrayOf(mid, bass, treble)
        val labels = arrayOf("Mids", "Bass", "Treble")
        for (i in 0..2) {
            val ex = cx + outerR * 0.88f * cos(angles[i]).toFloat()
            val ey = cy + outerR * 0.88f * sin(angles[i]).toFloat()
            drawLine(outline.copy(alpha = 0.2f), center, Offset(ex, ey), strokeWidth = 1f)
            for (dot in 1..7) {
                val r = baseR * (0.3f + dot * 0.2f)
                drawCircle(outline.copy(alpha = 0.5f), 3.5f, Offset(cx + r * cos(angles[i]).toFloat(), cy + r * sin(angles[i]).toFloat()))
            }
        }
        drawCircle(outline.copy(alpha = 0.1f), baseR, center, style = Stroke(0.8f))

        val wave = Path()
        val segments = 72
        for (s in 0..segments) {
            val angle = s.toDouble() / segments * 2.0 * PI - PI / 2
            var r = baseR
            for (i in 0..2) {
                val influence = baseR * (values[i] / 10f).coerceIn(-1f, 1f) * 0.8f
                val wrap = atan2(sin(angle - angles[i]), cos(angle - angles[i]))
                val w = cos(wrap * 0.75).toFloat().coerceAtLeast(0f)
                r += influence * w * w
            }
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (s == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        wave.close()
        drawPath(wave, accent.copy(alpha = 0.12f))
        drawPath(wave, accent.copy(alpha = 0.4f), style = Stroke(2f, cap = StrokeCap.Round))

        for (i in 0..2) {
            val r = baseR * (1f + (values[i] / 10f).coerceIn(-1f, 1f) * 0.8f)
            val point = Offset(cx + r * cos(angles[i]).toFloat(), cy + r * sin(angles[i]).toFloat())
            val isActive = active == i
            drawCircle(accent.copy(alpha = if (isActive) 0.2f else 0.1f), if (isActive) 32f else 26f, point)
            drawCircle(if (isActive) accent else Color.White, if (isActive) 18f else 14f, point)
        }

        for (i in 0..2) {
            val label = measurer.measure(labels[i], labelStyle)
            val value = measurer.measure((if (values[i] >= 0) "+" else "") + values[i].toInt(), valueStyle)
            val lr = outerR * 0.78f
            val lx = cx + lr * cos(angles[i]).toFloat()
            val ly = cy + lr * sin(angles[i]).toFloat()
            drawText(label, Color.White.copy(alpha = 0.6f), Offset(lx - label.size.width / 2f, ly - label.size.height - 2f))
            drawText(value, Color.White, Offset(lx - value.size.width / 2f, ly + 2f))
        }
    }
}

@Composable
private fun AdvancedEq(eq: EqualizerSettings, onTuned: () -> Unit, onSaved: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(26.dp))
                .background(ConvxColors.Card)
                .padding(vertical = 16.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                eq.bands.forEachIndexed { index, band ->
                    BandSlider(band, eq.enabled, onChange = { gain ->
                        updateEq { s -> s.copy(bands = s.bands.mapIndexed { i, b -> if (i == index) b.copy(gain = gain) else b }) }
                        onTuned()
                    }) {
                        editBand(index, band) { onTuned() }
                    }
                }
            }
        }
        SettingsSection {
            SliderRow(
                "Preamp",
                "%.1f dB".format(eq.preamp),
                eq.preamp.toFloat().coerceIn(Equalizer.MinPreamp.toFloat(), 0f),
                Equalizer.MinPreamp.toFloat()..0f,
                "Turn this down as far as your biggest boost, so loud parts don't distort.",
                enabled = eq.enabled,
            ) { v ->
                updateEq { it.copy(preamp = v.toDouble()) }
                onTuned()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            PillButton("Reset", {
                val (bands, preamp) = Equalizer.fromGains(List(10) { 0.0 })
                updateEq { it.copy(bands = bands, preamp = preamp) }
                onTuned()
            }, icon = "refresh")
            PillButton("Save", { askToSave(eq, onSaved) })
        }
        if (eq.presets.isNotEmpty()) {
            SettingsSection("Saved") {
                eq.presets.forEachIndexed { i, preset ->
                    if (i > 0) RowDivider(PlainInset)
                    val current = preset.bands == eq.bands && preset.preamp == eq.preamp
                    SettingsRow(
                        preset.name,
                        "${preset.bands.size} bands · preamp %.1f dB".format(preset.preamp),
                        onClick = {
                            updateEq { it.copy(bands = preset.bands, preamp = preset.preamp) }
                            onSaved()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (current) {
                                ConvxIcon("check", tint = LocalAccent.current, size = 18.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            PillButton("Delete", {
                                updateEq { s -> s.copy(presets = s.presets.filter { it.id != preset.id }) }
                            }, accent = SystemColors.Red)
                        }
                    }
                }
            }
        }
    }
}

/** One band standing up: its gain, a vertical glass slider, its frequency (click to edit) and Q. */
@Composable
private fun BandSlider(band: EqBand, enabled: Boolean, onChange: (Double) -> Unit, onEdit: () -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "%.1f".format(band.gain),
            style = ConvxType.CaptionStrong,
            color = if (enabled) accent else ConvxColors.TertiaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
            GlassSlider(
                band.gain.toFloat().coerceIn(-Equalizer.MaxGain.toFloat(), Equalizer.MaxGain.toFloat()),
                { onChange((it * 10).roundToInt() / 10.0) },
                Modifier
                    .width(200.dp)
                    // Stand the slider up: measure it lying down, then swap its width and height.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = constraints.minHeight, maxWidth = constraints.maxHeight),
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(-placeable.width / 2 + placeable.height / 2, placeable.width / 2 - placeable.height / 2)
                        }
                    }
                    .graphicsLayer { rotationZ = -90f },
                valueRange = -Equalizer.MaxGain.toFloat()..Equalizer.MaxGain.toFloat(),
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .clip(ConvxShapes.Capsule)
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onEdit) else Modifier)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(frequencyLabel(band.frequency), style = ConvxType.CaptionStrong, color = if (enabled) accent else ConvxColors.TertiaryLabel, maxLines = 1)
        }
        Text("Q %.2f".format(band.q), style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
    }
}

private fun frequencyLabel(hz: Double) = if (hz >= 1000) {
    val k = hz / 1000
    (if (k == k.toInt().toDouble()) "${k.toInt()}" else "%.1f".format(k)) + " kHz"
} else "${hz.roundToInt()} Hz"

private fun askToSave(eq: EqualizerSettings, onSaved: () -> Unit = {}) {
    Overlays.prompt("Save Preset", confirm = "Save") { name ->
        val preset = EqPreset("custom_${UUID.randomUUID()}", name.take(60), eq.bands, eq.preamp)
        updateEq { it.copy(presets = it.presets + preset) }
        onSaved()
        Overlays.toast("Preset saved")
    }
}

/** The phone's Manage Presets: tick the saved tunings to delete. */
private fun managePresets(presets: List<EqPreset>) {
    Overlays.showDialog {
        val chosen = remember { mutableStateListOf<String>() }
        DialogCard("Manage Presets") {
            Column(Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(18.dp)).background(Color.White.copy(alpha = 0.05f))) {
                presets.forEachIndexed { i, preset ->
                    if (i > 0) RowDivider(PlainInset)
                    CheckRow(preset.name, checked = preset.id in chosen) {
                        if (preset.id in chosen) chosen.remove(preset.id) else chosen.add(preset.id)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                if (chosen.isNotEmpty()) {
                    PillButton("Delete", {
                        val gone = chosen.toSet()
                        updateEq { s -> s.copy(presets = s.presets.filter { it.id !in gone }) }
                        Overlays.dismissDialog()
                    }, accent = SystemColors.Red)
                }
            }
        }
    }
}

/** The phone's Edit EQ band: the centre frequency and Q, typed in. */
private fun editBand(index: Int, band: EqBand, onApplied: () -> Unit) {
    Overlays.showDialog {
        var frequency by remember { mutableStateOf(band.frequency.let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }) }
        var q by remember { mutableStateOf("%.2f".format(band.q)) }
        val f = frequency.trim().toDoubleOrNull()
        val qv = q.trim().toDoubleOrNull()
        val valid = f != null && f.isFinite() && f in 20.0..20000.0 && qv != null && qv.isFinite() && qv in 0.1..20.0
        DialogCard("Edit EQ band") {
            DialogField("Center frequency (20–20000 Hz)", frequency) { frequency = it }
            Spacer(Modifier.height(12.dp))
            DialogField("Q / bandwidth (0.1–20)", q) { q = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (valid) {
                        updateEq { s -> s.copy(bands = s.bands.mapIndexed { i, b -> if (i == index) b.copy(frequency = f!!, q = qv!!) else b }) }
                        onApplied()
                        Overlays.dismissDialog()
                    }
                }, filled = valid)
            }
        }
    }
}

@Composable
internal fun DialogField(label: String, value: String, lines: Int = 1, secret: Boolean = false, onChange: (String) -> Unit) {
    DisposableEffect(label) { onDispose { UiState.typingFocusChanged(label, false) } }
    Column {
        Text(label, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ConvxShapes.Control)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = lines == 1,
                minLines = lines,
                maxLines = lines,
                textStyle = (if (lines == 1) ConvxType.Body else ConvxType.Subhead).copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onFocusChanged { UiState.typingFocusChanged(label, it.isFocused) },
            )
        }
    }
}

/**
 * Asks for an AutoEQ ParametricEQ file and adds it as a saved tuning, selected and shown in
 * Advanced, as the phone does.
 */
private fun importProfile() {
    val picker = FileDialog(null as Frame?, "Import a ParametricEQ preset", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".txt", ignoreCase = true) }
        file = "*.txt"
        isVisible = true
    }
    val name = picker.file ?: return
    val file = File(picker.directory, name)
    val parsed = runCatching {
        require(file.length() <= 64 * 1024) { "The file is bigger than 64 KB." }
        ParametricEq.parse(file.readText())
    }.getOrElse { error ->
        Log.w("Equalizer", "Couldn't import $file: ${error.message}")
        notice(
            "Couldn't import this preset",
            listOfNotNull(error.message, "Choose a ParametricEQ .txt file smaller than 64 KB with valid filters.").joinToString(" "),
        )
        return
    }
    val title = name.substringBeforeLast('.').trim().take(120).ifBlank { "Imported preset" }
    val preset = EqPreset("custom_${UUID.randomUUID()}", title, parsed.bands, parsed.preamp.coerceIn(Equalizer.MinPreamp, 0.0))
    updateEq { it.copy(presets = it.presets + preset, bands = preset.bands, preamp = preset.preamp, advanced = true) }
    Overlays.toast(
        if (AppSettings.value.equalizer.enabled) "Preset imported and selected" else "Preset imported and selected. Turn on the equalizer to hear it.",
    )
}

private fun notice(title: String, message: String) {
    Overlays.showDialog {
        DialogCard(title) {
            Text(message, style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("OK", { Overlays.dismissDialog() }, filled = true)
            }
        }
    }
}
