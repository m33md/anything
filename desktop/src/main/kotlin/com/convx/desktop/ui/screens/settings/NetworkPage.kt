/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.Network
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

/** The phone's Network & proxy page: the IP version and an HTTP or SOCKS proxy. */
@Composable
fun NetworkSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Proxy") {
        ChoiceRow(
            "IP version",
            "Which internet protocol Convx uses to reach YouTube",
            listOf("AUTO" to "Auto", "IPV4" to "IPv4", "IPV6" to "IPv6"),
            settings.ipVersion,
        ) { v -> change { it.copy(ipVersion = v) } }
        RowDivider(PlainInset)
        SwitchRow("Enable proxy", checked = settings.proxyEnabled) { v -> change { it.copy(proxyEnabled = v) } }
        RowDivider(PlainInset)
        val valid = Network.parse(settings.proxyType, settings.proxyUrl) != null
        SettingsRow(
            "Configure proxy",
            when {
                settings.proxyUrl.isBlank() -> "Not set"
                !valid -> "${settings.proxyUrl} isn't host:port"
                else -> "${settings.proxyType} · ${settings.proxyUrl}" + if (settings.proxyUsername.isNotEmpty()) " · ${settings.proxyUsername}" else ""
            },
            enabled = settings.proxyEnabled,
            onClick = { proxyDialog() },
        ) { ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp) }
    }
    Spacer(Modifier.height(12.dp))
    InfoNote(
        "Everything Convx fetches goes through the proxy: songs, covers, lyrics and your accounts. Logging in to Google " +
            "happens in your browser, which uses Windows' own proxy settings. The proxy password is kept encrypted on this PC.",
    )
}

private fun change(transform: (Settings) -> Settings) {
    AppSettings.update(transform)
    Network.apply(AppSettings.value)
}

/** The phone's proxy dialog: type, host:port, and optional sign-in. */
private fun proxyDialog() {
    Overlays.showDialog {
        val current = AppSettings.value
        var type by remember { mutableStateOf(current.proxyType) }
        var url by remember { mutableStateOf(current.proxyUrl) }
        var auth by remember { mutableStateOf(current.proxyUsername.isNotEmpty() || Secrets[Network.KeyProxyPassword] != null) }
        var user by remember { mutableStateOf(current.proxyUsername) }
        var password by remember { mutableStateOf(Secrets[Network.KeyProxyPassword].orEmpty()) }
        val valid = Network.parse(type, url) != null
        DialogCard("Configure proxy") {
            SegmentedControl(listOf("HTTP", "SOCKS"), if (type == "SOCKS") 1 else 0, Modifier.fillMaxWidth()) { i ->
                type = if (i == 1) "SOCKS" else "HTTP"
            }
            Spacer(Modifier.height(14.dp))
            DialogField("Proxy URL (host:port)", url) { url = it }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Enable authentication", checked = auth) { v ->
                auth = v
                if (!v) {
                    user = ""
                    password = ""
                }
            }
            AnimatedVisibility(auth) {
                Column {
                    DialogField("Username", user) { user = it }
                    Spacer(Modifier.height(10.dp))
                    DialogField("Password", password, secret = true) { password = it }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (valid) {
                        Secrets[Network.KeyProxyPassword] = password.takeIf { auth }
                        change { it.copy(proxyType = type, proxyUrl = url.trim(), proxyUsername = if (auth) user.trim() else "") }
                        Overlays.dismissDialog()
                    }
                }, filled = valid)
            }
            if (!valid && url.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Write it as host:port, for example 127.0.0.1:8080.", style = ConvxType.Subhead, color = SystemColors.Red)
            }
        }
    }
}
