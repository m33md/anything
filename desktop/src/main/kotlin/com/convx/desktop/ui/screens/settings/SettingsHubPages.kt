/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.applyLocale
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.integrations.DiscordPresence
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.player.AudioCache
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.screens.PageCache
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
fun PlayerHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Player") {
        SwitchRow("Wavy progress bar", "The phone's squiggly progress line while music plays.", settings.wavyProgress) { v ->
            AppSettings.update { it.copy(wavyProgress = v) }
        }
    }
    SectionGap()
    SettingsSection("Lyrics", footer = "Lyrics come from the same providers as on the phone, and open as soon as the song starts.") {
        SwitchRow("Word-by-word lyrics", "Light up each word as it's sung, when the lyrics have word timing.", settings.lyricsWordSync) { v ->
            AppSettings.update { it.copy(lyricsWordSync = v) }
        }
    }
}

@Composable
fun LibraryHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Region & filters") {
        ChoiceRow("Content country", "Which country's charts and recommendations you see.", Countries, settings.country) { code ->
            AppSettings.update { it.copy(country = code) }
            applyLocale(AppSettings.value)
            PageCache.clear()
        }
        RowDivider(PlainInset)
        ChoiceRow("Content language", "The language YouTube Music answers in.", Languages, settings.language) { code ->
            AppSettings.update { it.copy(language = code) }
            applyLocale(AppSettings.value)
            PageCache.clear()
        }
        RowDivider(PlainInset)
        SwitchRow("Hide explicit content", checked = settings.hideExplicit) { v -> AppSettings.update { it.copy(hideExplicit = v) } }
        RowDivider(PlainInset)
        SwitchRow("Hide music videos", "Show only audio tracks in feeds and search.", settings.hideVideos) { v -> AppSettings.update { it.copy(hideVideos = v) } }
    }
}

@Composable
fun AccountsHub() {
    val spotify by SpotifyAccount.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val discord by DiscordPresence.status.collectAsState()
    SettingsSection("Accounts") {
        LinkRow(
            "Spotify",
            "Your playlists and Liked Songs in Your Library",
            icon = "spotify",
            tint = SystemColors.Green,
            value = when (spotify) {
                is SpotifyAccount.State.SignedIn -> "Connected"
                SpotifyAccount.State.SigningIn -> "Signing in"
                is SpotifyAccount.State.Failed -> "Not connected"
                SpotifyAccount.State.SignedOut -> "Off"
            },
        ) { SettingsNav.push(SettingsPage.Spotify) }
        RowDivider()
        LinkRow(
            "Discord",
            "Listening status on your profile",
            icon = "discord",
            tint = SystemColors.Indigo,
            value = when {
                !settings.discordPresence -> "Off"
                discord == DiscordPresence.Status.Connected -> "On"
                discord == DiscordPresence.Status.NotRunning -> "Waiting"
                else -> "On"
            },
        ) { SettingsNav.push(SettingsPage.Discord) }
    }
    SectionGap()
    InfoNote("Signing in to YouTube Music, Last.fm scrobbling and imports from other apps are on their way, as on the phone.")
}

@Composable
fun PrivacyHub() {
    var audioBytes by remember { mutableLongStateOf(-1L) }
    var imageBytes by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            audioBytes = AudioCache.sizeBytes()
            imageBytes = ImageCache.diskBytes()
        }
    }
    SettingsSection("Storage") {
        SettingsRow("Song cache", "Songs you've played are kept so they start instantly next time. Capped at 1 GB.") {
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
        RowDivider(PlainInset)
        SettingsRow("Image cache", "Covers and artist photos.") {
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
    }
    SectionGap()
    SettingsSection("Your files", footer = "Your library, settings and sign-ins live in the app data folder. Copy it to keep a backup.") {
        SettingsRow("App data", AppDirs.data.absolutePath) {
            PillButton("Open folder", { openFolder(AppDirs.data) })
        }
        RowDivider(PlainInset)
        SettingsRow("Logs", AppDirs.logs.absolutePath) {
            PillButton("Open folder", { openFolder(AppDirs.logs) })
        }
    }
}

@Composable
fun HelpHub() {
    Row(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(26.dp)).background(ConvxColors.Card).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(ContinuousRoundedRectangle(15.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            ConvxIcon("convx_logo", tint = ConvxColors.AccentRed, size = 40.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Convx for desktop", style = ConvxType.Title3, color = ConvxColors.Label)
            Text("Version ${AppInfo.VERSION}", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        }
    }
    SectionGap()
    SettingsSection(footer = "Built from the Convx Android app's own YouTube Music and lyrics code. Licensed under GPL-3.0.") {
        LinkRow("Convx on GitHub", "The phone app's project", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://github.com/cosmictaserdev-creator/Convx")
        }
        RowDivider()
        LinkRow("Logs", "For when something goes wrong", icon = "bug_report", tint = SystemColors.Orange) { openFolder(AppDirs.logs) }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "…"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

internal fun openFolder(dir: java.io.File) {
    runCatching {
        dir.mkdirs()
        java.awt.Desktop.getDesktop().open(dir)
    }.onFailure { Overlays.toast("Couldn't open ${dir.absolutePath}", "error") }
}

internal fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        .onFailure { Overlays.toast("Couldn't open your browser", "error") }
}

/** A round avatar, or a coloured disc with a glyph while there's no picture. */
@Composable
internal fun AccountAvatar(url: String?, icon: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    if (url != null) {
        com.convx.desktop.ui.components.Artwork(url, Modifier.size(size), size = 256, shape = CircleShape, placeholderIcon = icon, iconSize = size * 0.45f)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            ConvxIcon(icon, tint = Color.White, size = size * 0.55f)
        }
    }
}
