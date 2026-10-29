/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.ui.player.LyricsFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps [ConvxType] in the font chosen in Settings, as the phone's theme does with its AppFont. */
object AppFonts {
    /** An imported font while it's switched on and still readable, else the chosen app font. */
    fun family(settings: Settings): FontFamily {
        if (settings.customFontEnabled && settings.customFont.isNotBlank()) imported(settings.customFont)?.let { return it }
        return AppFont.of(settings.appFont).family
    }

    fun imported(name: String): FontFamily? = runCatching {
        require(name.matches(Regex("custom-\\d+\\.font")))
        val file = LyricsFonts.dir.resolve(name)
        // Loading checks the file is a font before Compose ever draws with it.
        LyricsFonts.check(file)
        FontFamily(Font(file))
    }.onFailure { Log.w("Fonts", "Imported font $name can't be read", it) }.getOrNull()

    /** Sets the font before the window opens, then follows Settings. */
    fun start() {
        ConvxType.use(family(AppSettings.value))
        AppScope.launch(Dispatchers.Main) {
            AppSettings.state
                .map { Triple(it.appFont, it.customFontEnabled, it.customFont) }
                .distinctUntilChanged()
                .collect { ConvxType.use(family(AppSettings.value)) }
        }
    }
}
