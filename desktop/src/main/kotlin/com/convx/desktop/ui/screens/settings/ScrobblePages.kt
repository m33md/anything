/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Secrets
import com.convx.desktop.core.formatDuration
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.integrations.LastFm
import com.convx.desktop.integrations.ListenBrainz
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LastFmRed = Color(0xFFD51007)
private val ListenBrainzOrange = Color(0xFFEB743B)

/** The phone's Last.fm Integration page, plus the API key the PC needs. */
@Composable
fun LastFmSettings() {
    val state by LastFm.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val signedIn = state is LastFm.State.SignedIn
    AccountHeader(
        avatar = { AccountAvatar(null, "music_note", LastFmRed, 64.dp) },
        title = (state as? LastFm.State.SignedIn)?.user ?: "Last.fm",
        status = when (val s = state) {
            LastFm.State.NoKey -> "Not logged in. Add a Last.fm API key below first."
            LastFm.State.SignedOut -> "Not logged in. You approve Convx on Last.fm's own page; Convx never sees your password."
            LastFm.State.WaitingForBrowser -> "Approve Convx on the Last.fm page in your browser, then come back here."
            is LastFm.State.SignedIn -> if (settings.lastFmScrobble) "Scrobbling" else "Logged in, scrobbling paused"
            is LastFm.State.Failed -> s.message
        },
        statusIsError = state is LastFm.State.Failed,
    ) {
        when (state) {
            is LastFm.State.SignedIn -> PillButton("Log out", { LastFm.signOut() })
            LastFm.State.WaitingForBrowser -> PillButton("Cancel", { LastFm.cancelSignIn() })
            LastFm.State.NoKey -> PillButton("Add key", { editKey() }, accent = LastFmRed)
            else -> PillButton("Log in", {
                LastFm.signIn { uri -> openUrl(uri.toString()) }
            }, accent = LastFmRed)
        }
    }
    SectionGap()
    SettingsSection("Options") {
        SwitchRow("Enable scrobbling", checked = settings.lastFmScrobble, enabled = signedIn) { v ->
            AppSettings.update { it.copy(lastFmScrobble = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Send Now Playing", "Show the song on your Last.fm profile while it plays", settings.lastFmNowPlaying, enabled = signedIn) { v ->
            AppSettings.update { it.copy(lastFmNowPlaying = v) }
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Send Likes/Unlikes",
            "Love/Unlove songs in Last.fm when they are Liked/Unliked in Convx",
            settings.lastFmSendLikes,
            enabled = signedIn,
        ) { v -> AppSettings.update { it.copy(lastFmSendLikes = v) } }
    }
    SectionGap()
    ScrobbleRules(settings)
    SectionGap()
    val key = LastFm.apiKey
    SettingsSection("API key") {
        SettingsRow("API key and shared secret", key?.let { "${it.take(6)}…" } ?: "Not set", onClick = { editKey() }) {
            Text(if (key == null) "Add" else "Change", style = ConvxType.Body, color = SystemColors.Blue)
        }
        RowDivider(PlainInset)
        LinkRow("Get a free API key", "last.fm/api/account/create") { openUrl(LastFm.CreateKeyUrl) }
        if (key != null) {
            RowDivider(PlainInset)
            ButtonRow("Remove key and log out", color = SystemColors.Red) { LastFm.forgetKey() }
        }
    }
    Spacer(Modifier.height(12.dp))
    InfoNote(
        "Last.fm only lets an app scrobble with an API key of its own. The phone's is built into its release " +
            "and isn't public, so the PC app uses yours: make one at last.fm/api/account/create (any application " +
            "name, the callback URL can stay empty) and paste the API key and shared secret here. They stay on " +
            "this PC, encrypted for your Windows account.",
    )
}

/** Both halves of the key in one dialog, since Last.fm shows them together. */
private fun editKey() {
    Overlays.showDialog {
        var key by remember { mutableStateOf(LastFm.apiKey.orEmpty()) }
        var secret by remember { mutableStateOf("") }
        val valid = key.trim().length == 32 && (secret.trim().length == 32 || (secret.isBlank() && LastFm.apiKey == key.trim()))
        DialogCard("Last.fm API key") {
            DialogField("API key", key) { key = it }
            Spacer(Modifier.height(12.dp))
            DialogField("Shared secret", secret, secret = true) { secret = it }
            Spacer(Modifier.height(10.dp))
            Text(
                "Both are 32 letters and numbers, on the page Last.fm shows after you create the key.",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (valid) {
                        if (secret.isNotBlank()) LastFm.setKey(key, secret)
                        Overlays.dismissDialog()
                    }
                }, filled = valid)
            }
        }
    }
}

/** ListenBrainz's own page: the token, the switch and the shared scrobble rules. */
@Composable
fun ListenBrainzSettings() {
    val secrets by Secrets.values.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val connected = secrets.containsKey("listenbrainz.token")
    AccountHeader(
        avatar = { AccountAvatar(null, "music_history", ListenBrainzOrange, 64.dp) },
        title = if (connected) ListenBrainz.user ?: "ListenBrainz" else "ListenBrainz",
        status = if (connected) "ListenBrainz token set" else "Not set up. Paste your user token from listenbrainz.org/settings.",
    ) {
        if (connected) {
            PillButton("Remove", { ListenBrainz.forget() })
        } else {
            PillButton("Set token", { askForToken() }, accent = ListenBrainzOrange)
        }
    }
    SectionGap()
    SettingsSection {
        SwitchRow(
            "ListenBrainz scrobbling",
            "Automatically submit your listened tracks to ListenBrainz",
            settings.listenBrainzScrobble,
            enabled = connected,
        ) { v -> AppSettings.update { it.copy(listenBrainzScrobble = v) } }
        RowDivider(PlainInset)
        LinkRow("Your user token", "listenbrainz.org/settings") { openUrl(ListenBrainz.TokenUrl) }
        if (connected) {
            RowDivider(PlainInset)
            ButtonRow("Change token") { askForToken() }
        }
    }
    SectionGap()
    ScrobbleRules(settings)
}

private fun askForToken() {
    Overlays.prompt("ListenBrainz token", confirm = "Save") { token ->
        AppScope.launch {
            val result = ListenBrainz.setToken(token)
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { Overlays.toast("Connected to ListenBrainz as $it") }
                    .onFailure { Overlays.toast("ListenBrainz didn't accept that token", "error") }
            }
        }
    }
}

/** The phone's three scrobble dials, shared by Last.fm and ListenBrainz. */
@Composable
private fun ScrobbleRules(settings: Settings) {
    SettingsSection("Scrobbling", footer = "A song counts once you've heard the shorter of the two delays. Paused time doesn't count.") {
        SliderRow(
            "Scrobble songs longer than",
            formatDuration(settings.scrobbleMinSeconds * 1000L),
            settings.scrobbleMinSeconds.toFloat(),
            10f..60f,
        ) { v -> AppSettings.update { it.copy(scrobbleMinSeconds = v.roundToInt()) } }
        RowDivider(PlainInset)
        SliderRow(
            "Scrobble delay percent",
            "${(settings.scrobbleDelayPercent * 100).roundToInt()}%",
            settings.scrobbleDelayPercent,
            0.3f..0.95f,
        ) { v -> AppSettings.update { it.copy(scrobbleDelayPercent = (v * 100).roundToInt() / 100f) } }
        RowDivider(PlainInset)
        SliderRow(
            "Scrobble delay minutes",
            formatDuration(settings.scrobbleDelaySeconds * 1000L),
            settings.scrobbleDelaySeconds.toFloat(),
            30f..360f,
        ) { v -> AppSettings.update { it.copy(scrobbleDelaySeconds = (v / 10).roundToInt() * 10) } }
    }
}
