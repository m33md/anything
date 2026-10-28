/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.music.innertube.YouTube
import com.music.innertube.models.IpVersion
import com.music.innertube.models.YouTubeLocale
import java.util.Locale

object AppInfo {
    const val VERSION = "1.0.0"
}

/** The phone's App.initializeSettings locale rule: setting, else the system's, else US/English. */
fun applyLocale(settings: Settings) {
    val system = Locale.getDefault()
    YouTube.locale = YouTubeLocale(
        gl = settings.country.ifBlank { system.country.takeIf { it.length == 2 } ?: "US" },
        hl = settings.language.ifBlank { system.language.takeIf { it.isNotBlank() } ?: "en" },
    )
}

/** Configures the shared YouTube client the way the phone does before its first request. */
suspend fun initYouTube() {
    val settings = AppSettings.value
    applyLocale(settings)
    YouTube.ipVersion = IpVersion.IPV4
    YouTube.cookie = settings.cookie
    val saved = settings.visitorData?.takeIf { it.isNotBlank() && it != "null" }
    if (saved != null) {
        YouTube.visitorData = saved
    } else {
        YouTube.visitorData().onSuccess { fresh ->
            YouTube.visitorData = fresh
            AppSettings.update { it.copy(visitorData = fresh) }
        }.onFailure { Log.w("Startup", "Couldn't get visitor data", it) }
    }
}
