/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.applyLocale
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.player.AudioCache
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Divider
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's accent swatches. */
private val Accents = listOf(
    "Red" to Color(0xFFFA2D48),
    "Pink" to Color(0xFFFF375F),
    "Orange" to Color(0xFFFF9F0A),
    "Yellow" to Color(0xFFFFD60A),
    "Green" to Color(0xFF30D158),
    "Mint" to Color(0xFF63E6E2),
    "Blue" to Color(0xFF0A84FF),
    "Indigo" to Color(0xFF5E5CE6),
    "Purple" to Color(0xFFBF5AF2),
)

private val Countries = listOf(
    "" to "Automatic", "US" to "United States", "GB" to "United Kingdom", "CA" to "Canada", "AU" to "Australia",
    "IN" to "India", "SA" to "Saudi Arabia", "AE" to "United Arab Emirates", "EG" to "Egypt", "MA" to "Morocco",
    "TR" to "Türkiye", "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "NL" to "Netherlands",
    "SE" to "Sweden", "BR" to "Brazil", "MX" to "Mexico", "AR" to "Argentina", "JP" to "Japan", "KR" to "South Korea",
    "ID" to "Indonesia", "PH" to "Philippines", "NG" to "Nigeria", "ZA" to "South Africa", "PK" to "Pakistan",
)

private val Languages = listOf(
    "" to "Automatic", "en" to "English", "ar" to "العربية", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
    "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe", "hi" to "हिन्दी", "ur" to "اردو", "id" to "Bahasa Indonesia",
    "ja" to "日本語", "ko" to "한국어", "ru" to "Русский", "nl" to "Nederlands",
)

@Composable
fun SettingsScreen() {
    val settings by AppSettings.state.collectAsState()
    var audioBytes by remember { mutableLongStateOf(-1L) }
    var imageBytes by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            audioBytes = AudioCache.sizeBytes()
            imageBytes = ImageCache.diskBytes()
        }
    }

    PageList {
        item { Text("Settings", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }

        item {
            SettingsGroup("Appearance") {
                SettingRow("Accent color", "Buttons, the playing song and highlights.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = settings.accent?.let { Color(it) } ?: Accents.first().second
                        Accents.forEach { (name, color) ->
                            Tooltip(name) {
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        // A 0.dp border still draws a hairline, so only add one when selected.
                                        .then(if (color == current) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                                        .pressable({ AppSettings.update { it.copy(accent = color.toArgb().toLong() and 0xFFFFFFFFL) } }),
                                )
                            }
                        }
                    }
                }
                Divider()
                ToggleRow("Moving color background", "Fill the window with soft colors from the current cover, like the phone.", settings.dynamicBackground) { v ->
                    AppSettings.update { it.copy(dynamicBackground = v) }
                }
                Divider()
                ToggleRow("Wavy progress bar", "The phone's squiggly progress line while music plays.", settings.wavyProgress) { v ->
                    AppSettings.update { it.copy(wavyProgress = v) }
                }
            }
        }

        item {
            SettingsGroup("Playback") {
                ChoiceRow(
                    "Audio quality",
                    "High picks the best stream YouTube offers. Low saves data.",
                    AudioQuality.entries.map { it to it.name.lowercase().replaceFirstChar(Char::titlecase) },
                    settings.audioQuality,
                ) { q -> AppSettings.update { it.copy(audioQuality = q) } }
                Divider()
                ToggleRow("Autoplay similar songs", "When your queue ends, keep going with a radio of the last song.", settings.autoRadio) { v ->
                    AppSettings.update { it.copy(autoRadio = v) }
                }
            }
        }

        item {
            SettingsGroup("Lyrics") {
                ToggleRow("Word-by-word lyrics", "Light up each word as it is sung, when the lyrics have word timing.", settings.lyricsWordSync) { v ->
                    AppSettings.update { it.copy(lyricsWordSync = v) }
                }
            }
        }

        item {
            SettingsGroup("Content") {
                ChoiceRow("Content country", "Which country's charts and recommendations you see.", Countries, settings.country) { code ->
                    AppSettings.update { it.copy(country = code) }
                    applyLocale(AppSettings.value)
                    PageCache.clear()
                }
                Divider()
                ChoiceRow("Content language", "The language YouTube Music answers in.", Languages, settings.language) { code ->
                    AppSettings.update { it.copy(language = code) }
                    applyLocale(AppSettings.value)
                    PageCache.clear()
                }
                Divider()
                ToggleRow("Hide explicit content", null, settings.hideExplicit) { v -> AppSettings.update { it.copy(hideExplicit = v) } }
                Divider()
                ToggleRow("Hide music videos", "Show only audio tracks in feeds and search.", settings.hideVideos) { v -> AppSettings.update { it.copy(hideVideos = v) } }
            }
        }

        item {
            SettingsGroup("Storage") {
                SettingRow("Song cache", "Songs you've played are kept so they start instantly next time. Capped at 1 GB.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatBytes(audioBytes), style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                        Spacer(Modifier.width(12.dp))
                        PillButton("Clear", {
                            AudioCache.clear()
                            refresh++
                            Overlays.toast("Song cache cleared")
                        })
                    }
                }
                Divider()
                SettingRow("Image cache", "Covers and artist photos.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatBytes(imageBytes), style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                        Spacer(Modifier.width(12.dp))
                        PillButton("Clear", {
                            ImageCache.clearDisk()
                            refresh++
                            Overlays.toast("Image cache cleared")
                        })
                    }
                }
                Divider()
                SettingRow("App data", AppDirs.data.absolutePath) {
                    PillButton("Open folder", { openFolder(AppDirs.data) })
                }
                Divider()
                SettingRow("Logs", AppDirs.logs.absolutePath) {
                    PillButton("Open folder", { openFolder(AppDirs.logs) })
                }
            }
        }

        item {
            SettingsGroup("About") {
                SettingRow("Convx for desktop", "Version ${AppInfo.VERSION}. Built from the Convx Android app's own YouTube Music and lyrics code. Licensed under GPL-3.0.") {
                    ConvxIcon("convx_logo", tint = LocalAccent.current, size = 32.dp)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Gutter, vertical = 10.dp).widthIn(max = 920.dp)) {
        Text(title.uppercase(), style = ConvxType.CaptionStrong, color = ConvxColors.SecondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(ConvxShapes.CardLarge).background(Color.White.copy(alpha = 0.06f))) {
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = ConvxType.Body)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
        }
        trailing()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.pressable({ onChange(!checked) }, pressScale = 1f)) {
        SettingRow(title, subtitle) { Toggle(checked, onChange) }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, subtitle: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val menu = rememberMenuAnchor()
    SettingRow(title, subtitle) {
        Box {
            PillButton(options.firstOrNull { it.first == selected }?.second ?: options.first().second, { menu.expanded = true }, icon = "expand_more")
            AppMenu(menu) {
                options.forEach { (value, label) ->
                    MenuItem(label, if (value == selected) "check" else null) {
                        onSelect(value)
                        menu.expanded = false
                    }
                }
            }
        }
    }
}

/** iOS switch: 51 x 31, accent when on. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) LocalAccent.current else Color.White.copy(alpha = 0.16f))
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp)
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .pressable({ onChange(!checked) }, pressScale = 0.95f),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "…"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun openFolder(dir: java.io.File) {
    runCatching {
        dir.mkdirs()
        java.awt.Desktop.getDesktop().open(dir)
    }.onFailure { Overlays.toast("Couldn't open ${dir.absolutePath}", "error") }
}
