/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.player.LyricsFonts
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.AppFont
import com.convx.desktop.ui.theme.AppFonts
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The phone's App Font page: a preview in the font in use, its five fonts each shown in itself,
 * and one of your own. The phone's "Display font" switch is left out because on the phone it
 * changes nothing, and here, unlike there, an imported font shows whichever font is ticked.
 */
@Composable
fun AppFontSettings() {
    val settings by AppSettings.state.collectAsState()
    val custom = settings.customFontEnabled && settings.customFont.isNotBlank()
    val chosen = AppFont.of(settings.appFont)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(26.dp))
            .background(ConvxColors.Card)
            .padding(24.dp),
    ) {
        Text("TYPOGRAPHY PREVIEW", style = ConvxType.CaptionStrong.copy(fontWeight = FontWeight.Bold), color = SystemColors.Red)
        Spacer(Modifier.height(8.dp))
        Text(
            "“Where words fail, music speaks.”",
            style = TextStyle(fontFamily = ConvxType.family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            color = ConvxColors.Label,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Every title, label and line of text in Convx is set in this font, and so are lyrics unless Lyrics effects gives them their own.",
            style = ConvxType.Subhead,
            color = ConvxColors.SecondaryLabel,
        )
    }
    SectionGap()
    SettingsSection("Font Selection") {
        AppFont.entries.forEachIndexed { i, font ->
            if (i > 0) RowDivider(start = 54.dp)
            FontRow(font.label, font.description, font.family, selected = !custom && font == chosen) {
                AppSettings.update { it.copy(appFont = font.id, customFontEnabled = false) }
            }
        }
    }
    SectionGap()
    SettingsSection(footer = "A .ttf or .otf file of up to 8 MB. It's copied into Convx, so the original can be moved or deleted.") {
        val installed = settings.customFont.isNotBlank()
        SettingsRow(
            "Custom font",
            subtitle = when {
                !installed -> "Click to install a .ttf or .otf font, applied app-wide"
                settings.customFontName.isNotBlank() -> "${settings.customFontName} — click to replace"
                else -> "Custom font installed — click to replace"
            },
            icon = "alphabet_cyrillic",
            tint = SystemColors.Gray,
            onClick = ::importAppFont,
            trailing = if (installed) {
                {
                    GlassSwitch(custom, { on -> AppSettings.update { it.copy(customFontEnabled = on) } })
                }
            } else {
                null
            },
        )
    }
}

/** A font as the phone lists it: a radio, then its name and description drawn in the font itself. */
@Composable
private fun FontRow(title: String, description: String, family: FontFamily, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowPress(onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium), color = ConvxColors.Label)
            Text(description, style = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp), color = ConvxColors.SecondaryLabel)
        }
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val ring by animateColorAsState(if (selected) SystemColors.Red else ConvxColors.SecondaryLabel, tween(180))
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, tween(180))
    Box(Modifier.size(22.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dot).clip(CircleShape).background(SystemColors.Red))
    }
}

private fun importAppFont() {
    val file = pickFile("Install a TTF or OTF font", listOf("ttf", "otf")) ?: return
    AppScope.launch(Dispatchers.IO) {
        runCatching {
            val name = LyricsFonts.import(file)
            requireNotNull(AppFonts.imported(name)) { "This isn't a font Convx can read" }
            name
        }.onSuccess { name ->
            AppSettings.update { it.copy(customFont = name, customFontName = file.nameWithoutExtension, customFontEnabled = true) }
            Overlays.toast("Font installed")
        }.onFailure { Overlays.toast(it.message ?: "Couldn't install this font", "error") }
    }
}
