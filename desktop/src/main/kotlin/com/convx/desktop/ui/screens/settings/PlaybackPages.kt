/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.player.AudioEngine
import kotlin.math.roundToInt

/** The phone's Playback & Audio: sound, then the queue, then where it plays. */
@Composable
fun PlaybackHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Sound") {
        LinkRow("Equalizer", "Shape the sound", icon = "equalizer", tint = SystemColors.Green, value = onOff(settings.equalizer.enabled)) {
            SettingsNav.push(SettingsPage.Equalizer)
        }
        RowDivider()
        LinkRow("Quality & sound", "Audio quality, normalization and skip silence", icon = "tune", tint = SystemColors.Blue) {
            SettingsNav.push(SettingsPage.QualitySound)
        }
        RowDivider()
        LinkRow("Song transitions", "Crossfade between songs", icon = "ic_queue_crossfade", tint = SystemColors.Purple, value = onOff(settings.crossfade)) {
            SettingsNav.push(SettingsPage.Transitions)
        }
    }
    SectionGap()
    SettingsSection("Queue & behaviour") {
        LinkRow("Queue & autoplay", "Persistent queue, similar songs, shuffle memory", icon = "queue_music", tint = SystemColors.Orange) {
            SettingsNav.push(SettingsPage.Queue)
        }
        RowDivider()
        LinkRow("Sound output", "Which speakers or headphones Convx plays on", icon = "speaker_apple", tint = SystemColors.Indigo, value = settings.outputDevice ?: "Default") {
            SettingsNav.push(SettingsPage.Output)
        }
    }
    SectionGap()
    InfoNote(
        "The phone's offload, bit-perfect output, JioSaavn source and AI DJ analysis aren't on PC. " +
            "Offload and bit-perfect are Android audio features; the other two may come later.",
    )
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
fun QualitySoundSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection {
        ChoiceRow(
            "Audio quality",
            "High picks the best stream YouTube offers. Low saves data.",
            AudioQuality.entries.map { it to it.name.lowercase().replaceFirstChar(Char::titlecase) },
            settings.audioQuality,
            icon = "tune",
            tint = SystemColors.Blue,
        ) { q -> AppSettings.update { it.copy(audioQuality = q) } }
        RowDivider()
        SwitchRow(
            "Audio normalization",
            "Evens out loud and quiet songs with YouTube's loudness for each one. Starts with the next song.",
            settings.normalizeAudio,
            icon = "volume_up",
            tint = SystemColors.Red,
        ) { v -> AppSettings.update { it.copy(normalizeAudio = v) } }
        RowDivider()
        SwitchRow(
            "Skip silence",
            "Fast forward through silent parts of songs",
            settings.skipSilence,
            icon = "fast_forward",
            tint = SystemColors.Gray,
        ) { v -> AppSettings.update { it.copy(skipSilence = v) } }
    }
}

/** The phone's Song transitions, with crossfade; AutoMix needs analysis the PC doesn't have yet. */
@Composable
fun SongTransitionsSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection(footer = "Beginnings and endings of songs blend together. Albums still play without transitions.") {
        SwitchRow("Song transitions", checked = settings.crossfade, icon = "ic_queue_crossfade", tint = SystemColors.Purple) { v ->
            AppSettings.update { it.copy(crossfade = v) }
        }
    }
    SectionGap()
    SettingsSection("Transition style") {
        CheckRow(
            "AutoMix",
            "Songs transition at the perfect moment, based on the tempo and beats. Needs the phone's beat analysis, which isn't on PC yet.",
            checked = false,
            icon = "equalizer",
            tint = SystemColors.Pink,
            enabled = false,
        ) {}
        RowDivider()
        CheckRow(
            "Crossfade",
            "Simple song transitions from one to the next for a set duration.",
            checked = settings.crossfade,
            icon = "ic_queue_crossfade",
            tint = SystemColors.Purple,
            enabled = settings.crossfade,
        ) {}
    }
    if (settings.crossfade) {
        SectionGap()
        SettingsSection("Crossfade duration") {
            val seconds = settings.crossfadeSeconds.coerceIn(1f, 12f).roundToInt()
            SliderRow(
                if (seconds == 1) "1 second" else "$seconds seconds",
                "1s – 12s",
                seconds.toFloat(),
                1f..12f,
            ) { v -> AppSettings.update { it.copy(crossfadeSeconds = v.roundToInt().toFloat()) } }
        }
    }
}

@Composable
fun QueueSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection {
        SwitchRow("Persistent queue", "Restore your last queue when the app starts", settings.persistentQueue) { v ->
            AppSettings.update { it.copy(persistentQueue = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Enable similar content", "Automatically add more similar songs when the end of the queue is reached", settings.autoRadio) { v ->
            AppSettings.update { it.copy(autoRadio = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Remember shuffle and repeat", "Remember shuffle and repeat mode when restarting the app", settings.rememberShuffleRepeat) { v ->
            AppSettings.update { it.copy(rememberShuffleRepeat = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Auto skip to next song when error occurs", "Ensure your continuous playback experience", settings.skipOnError) { v ->
            AppSettings.update { it.copy(skipOnError = v) }
        }
    }
    SectionGap()
    SettingsSection(
        "Downloads",
        footer = "Downloads play with no connection. They're in Library under Downloaded, and Privacy & Storage shows how much room they take.",
    ) {
        SwitchRow("Auto download on like", "Automatically download songs when you like them", settings.autoDownloadOnLike) { v ->
            AppSettings.update { it.copy(autoDownloadOnLike = v) }
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Save downloads to Music folder",
            "Also keep each download in Music\\Convx as Artist - Title, with its lyrics, playable in any app",
            settings.saveDownloadsToMusic,
        ) { v ->
            AppSettings.update { it.copy(saveDownloadsToMusic = v) }
        }
    }
}

/** The PC's answer to the phone's Bluetooth & output: pick the speakers or headphones. */
@Composable
fun OutputSettings() {
    val settings by AppSettings.state.collectAsState()
    var devices by remember { mutableStateOf(AudioEngine.outputDevices()) }
    val chosen = settings.outputDevice
    SettingsSection(
        "Play on",
        footer = "Plugged in something new? Refresh the list. The phone's resume-on-Bluetooth and Google Cast are phone-only.",
    ) {
        CheckRow("Windows default", "Follows the output chosen in Windows' sound settings", chosen == null, icon = "speaker_apple", tint = SystemColors.Indigo) {
            AppSettings.update { it.copy(outputDevice = null) }
        }
        devices.forEach { name ->
            RowDivider()
            CheckRow(name, checked = chosen == name, icon = "volume_up", tint = SystemColors.Gray) {
                AppSettings.update { it.copy(outputDevice = name) }
            }
        }
        if (chosen != null && chosen !in devices) {
            RowDivider()
            CheckRow(chosen, "Not connected; Convx is using the Windows default until it's back", true, icon = "volume_off", tint = SystemColors.Gray) {}
        }
    }
    SectionGap()
    SettingsSection {
        ButtonRow("Refresh") { devices = AudioEngine.outputDevices() }
    }
}
