/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.Downloads
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The phone's Privacy page: listen and search history, and keeping the window out of screenshots. */
@Composable
fun PrivacySettings() {
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()

    SettingsSection("Listen history", footer = "While it's paused, songs you play don't show in History, Your Top 50 or Home's recently played. Last.fm and ListenBrainz still scrobble.") {
        SwitchRow("Pause listen history", checked = settings.pauseListenHistory, icon = "history", tint = SystemColors.Orange) { v ->
            AppSettings.update { it.copy(pauseListenHistory = v) }
        }
        RowDivider()
        SettingsRow(
            "Clear listen history",
            if (library.history.isEmpty()) "Nothing to clear" else "${library.history.size} plays",
            icon = "delete",
            tint = SystemColors.Red,
            enabled = library.history.isNotEmpty(),
            onClick = {
                Overlays.confirm("Clear listen history?", "Are you sure you want to clear all listen history?", "Clear") {
                    Library.clearHistory()
                    Overlays.toast("Listen history cleared", "delete")
                }
            },
        )
    }
    SectionGap()
    SettingsSection("Search history") {
        SwitchRow("Pause search history", checked = settings.pauseSearchHistory, icon = "search", tint = SystemColors.Blue) { v ->
            AppSettings.update { it.copy(pauseSearchHistory = v) }
        }
        RowDivider()
        SettingsRow(
            "Clear search history",
            if (library.searchHistory.isEmpty()) "Nothing to clear" else "${library.searchHistory.size} searches",
            icon = "delete",
            tint = SystemColors.Red,
            enabled = library.searchHistory.isNotEmpty(),
            onClick = {
                Overlays.confirm("Clear search history?", "Are you sure you want to clear all search history?", "Clear") {
                    Library.clearSearches()
                    Overlays.toast("Search history cleared", "delete")
                }
            },
        )
    }
    SectionGap()
    SettingsSection(
        "Privacy",
        footer = "Screenshots, screen recordings and screen sharing (Discord, Teams, OBS) show nothing where Convx is. " +
            "Windows 10 before version 2004 shows a black window instead.",
    ) {
        SwitchRow(
            "Disable screenshot",
            "Keep Convx's window out of screenshots and screen sharing",
            settings.disableScreenshot,
            icon = "security",
            tint = SystemColors.Indigo,
        ) { v -> AppSettings.update { it.copy(disableScreenshot = v) } }
    }
}

/** The phone's Storage page: downloads, and how big the song and image caches may grow. */
@Composable
fun StorageSettings() {
    val settings by AppSettings.state.collectAsState()
    val downloaded by Downloads.done.collectAsState()
    var songBytes by remember { mutableLongStateOf(-1L) }
    var imageBytes by remember { mutableLongStateOf(-1L) }
    var downloadBytes by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableIntStateOf(0) }
    // Sizes change as songs play and covers load, so they're read again every few seconds, as the phone does.
    LaunchedEffect(refresh, downloaded.size) {
        while (true) {
            withContext(Dispatchers.IO) {
                songBytes = AudioCache.sizeBytes()
                imageBytes = ImageCache.diskBytes()
                downloadBytes = Downloads.sizeBytes()
            }
            delay(3_000)
        }
    }

    SettingsSection("Usage", footer = "Clearing a cache never touches downloads, and copies in your Music folder stay when downloads are cleared.") {
        SettingsRow(
            "Downloaded songs",
            "${downloaded.size} ${if (downloaded.size == 1) "song" else "songs"} · ${formatBytes(downloadBytes)}",
            icon = "download",
            tint = SystemColors.Green,
        ) {
            PillButton("Open folder", { openFolder(Downloads.dir) })
        }
        RowDivider()
        SettingsRow(
            "Clear all downloads",
            icon = "delete",
            tint = SystemColors.Red,
            enabled = downloaded.isNotEmpty(),
            onClick = {
                Overlays.confirm("Clear all downloads?", "Are you sure you want to clear all downloads? They stay in your library.", "Clear") {
                    Downloads.removeAll()
                    refresh++
                    Overlays.toast("Downloads cleared", "delete")
                }
            },
        )
    }
    SectionGap()
    SettingsSection("Song cache", footer = "Songs you've played are kept so they start instantly and play again without the internet. Off keeps only the song that's playing.") {
        CacheLimit(
            "Max song cache size",
            SongCacheSteps,
            settings.maxSongCacheMb,
            songBytes,
            what = "songs",
        ) { mb ->
            AppSettings.update { it.copy(maxSongCacheMb = mb) }
            AppScope.launch(Dispatchers.IO) {
                AudioCache.trim()
                refresh++
            }
        }
        RowDivider(PlainInset)
        SettingsRow("Clear song cache", icon = "delete", tint = SystemColors.Red, enabled = songBytes > 0, onClick = {
            Overlays.confirm("Clear song cache?", "Are you sure you want to clear all cached songs?", "Clear") {
                AudioCache.clear()
                refresh++
                Overlays.toast("Song cache cleared", "delete")
            }
        })
    }
    SectionGap()
    SettingsSection("Image cache", footer = "Covers and artist photos. With it off, every picture is fetched again each time it's shown.") {
        CacheLimit(
            "Max image cache size",
            ImageCacheSteps,
            settings.maxImageCacheMb,
            imageBytes,
            what = "images",
        ) { mb ->
            AppSettings.update { it.copy(maxImageCacheMb = mb) }
            AppScope.launch(Dispatchers.IO) {
                if (mb == 0) ImageCache.clearDisk() else ImageCache.trimDisk()
                refresh++
            }
        }
        RowDivider(PlainInset)
        SettingsRow("Clear image cache", icon = "delete", tint = SystemColors.Red, enabled = imageBytes > 0, onClick = {
            Overlays.confirm("Clear image cache?", "Are you sure you want to clear all cached images?", "Clear") {
                ImageCache.clearDisk()
                refresh++
                Overlays.toast("Image cache cleared", "delete")
            }
        })
    }
    SectionGap()
    SettingsSection("Your files", footer = "Your library, settings and sign-ins live in the app data folder.") {
        SettingsRow("App data", AppDirs.data.absolutePath) {
            PillButton("Open folder", { openFolder(AppDirs.data) })
        }
        RowDivider(PlainInset)
        SettingsRow("Logs", AppDirs.logs.absolutePath) {
            PillButton("Open folder", { openFolder(AppDirs.logs) })
        }
    }
}

/** The phone's sizes, in MB; -1 is no limit. */
private val SongCacheSteps = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1)
private val ImageCacheSteps = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192)

private fun capLabel(mb: Int): String = when {
    mb == 0 -> "Off"
    mb < 0 -> "Unlimited"
    mb < 1024 -> "$mb MB"
    else -> "${mb / 1024} GB"
}

private fun capBytes(mb: Int): Long = if (mb < 0) Long.MAX_VALUE else mb * 1024L * 1024L

/**
 * The phone's cache limit: a slider over its fixed sizes, a bar of how full the cache is, and the
 * phone's "Hold on!" before a limit below what's in use deletes anything.
 */
@Composable
private fun CacheLimit(title: String, steps: List<Int>, current: Int, used: Long, what: String, onSet: (Int) -> Unit) {
    val index = steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOf(1024).coerceAtLeast(0)
    var dragged by remember(current) { mutableFloatStateOf(index.toFloat()) }
    val shown = steps[dragged.roundToInt().coerceIn(steps.indices)]
    Column {
        SliderRow(
            title,
            capLabel(shown),
            dragged,
            0f..(steps.size - 1).toFloat(),
            onChangeFinished = {
                val chosen = steps[dragged.roundToInt().coerceIn(steps.indices)]
                if (chosen != current && capBytes(chosen) < used) {
                    // The slider goes back until the change is confirmed.
                    dragged = index.toFloat()
                    Overlays.confirm(
                        "Hold on!",
                        "You've chosen a cache size limit smaller than what the app is currently using (${formatBytes(used)}). " +
                            "If you continue, the app may remove some cached $what to match the new limit. Proceed anyway?",
                        "Continue",
                    ) { onSet(chosen) }
                } else if (chosen != current) {
                    onSet(chosen)
                }
            },
        ) { dragged = it.roundToInt().toFloat() }
        val cap = capBytes(current)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            if (current != 0) {
                val fraction = if (current < 0 || used <= 0) 0f else (used.toFloat() / cap).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(SystemColors.Blue))
                }
                Text(
                    if (current < 0) formatBytes(used) else "${formatBytes(used)} / ${formatBytes(cap)}",
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text("${formatBytes(used)} in use", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
        }
    }
}
