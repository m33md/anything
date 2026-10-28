/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.OverlayHost
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.Playback
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.panel
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.player.FullPlayer
import com.convx.desktop.ui.player.MeshBackground
import com.convx.desktop.ui.player.PlayerBar
import com.convx.desktop.ui.player.RightPanel
import com.convx.desktop.ui.screens.ScreenHost
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxTheme

/**
 * The window: Spotify's arrangement (library on the left, pages in the middle,
 * now playing on the right, transport along the bottom) over the phone's ambient
 * artwork backdrop.
 */
@Composable
fun AppShell(navigator: Navigator) {
    val settings by AppSettings.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val accent = settings.accent?.let { Color(it) } ?: ConvxColors.AccentRed
    val playback = remember(player.current?.id, player.isPlaying, player.isLoading) {
        Playback(player.current?.id, player.isPlaying, player.isLoading)
    }

    // Playback problems surface as a toast, then clear.
    LaunchedEffect(player.error) {
        val error = player.error ?: return@LaunchedEffect
        Overlays.toast(error, "error")
        PlayerController.dismissError()
    }

    ConvxTheme(accent) {
        CompositionLocalProvider(LocalNavigator provides navigator, LocalPlayback provides playback) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (settings.dynamicBackground) {
                    val mesh by rememberMeshColors(artworkUrl(player.current?.thumbnail, 226))
                    MeshBackground(mesh, player.current?.id, scrim = 0.78f)
                }
                Column(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        // Narrow windows fold the library into its icon rail and drop the side panel.
                        val autoCollapse = maxWidth < 1000.dp
                        val panel = SidePanel.of(settings.rightPanel).takeIf { maxWidth >= 1100.dp }
                        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                            Sidebar(collapsed = settings.sidebarCollapsed || autoCollapse)
                            Spacer(Modifier.width(8.dp))
                            ContentPanel(navigator, Modifier.weight(1f).fillMaxHeight())
                            AnimatedVisibility(
                                visible = panel != null,
                                enter = fadeIn() + slideInHorizontally { it / 3 },
                                exit = fadeOut() + slideOutHorizontally { it / 3 },
                            ) {
                                Row {
                                    Spacer(Modifier.width(8.dp))
                                    // Keep the last panel on screen while it slides out.
                                    val shown = remember { mutableListOf<SidePanel>() }
                                    if (panel != null) {
                                        shown.clear()
                                        shown += panel
                                    }
                                    shown.firstOrNull()?.let { RightPanel(it) }
                                }
                            }
                        }
                    }
                    PlayerBar()
                }

                AnimatedVisibility(
                    visible = UiState.fullPlayer,
                    enter = slideInVertically { it / 6 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.98f),
                    exit = slideOutVertically { it / 6 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.98f),
                ) {
                    FullPlayer()
                }

                OverlayHost()
            }
        }
    }
}

@Composable
private fun ContentPanel(navigator: Navigator, modifier: Modifier) {
    val holder = rememberSaveableStateHolder()
    // Drop saved scroll positions for history entries that can't be reached any more.
    LaunchedEffect(navigator) {
        snapshotFlow { navigator.discarded.toList() }.collect { keys ->
            if (keys.isNotEmpty()) {
                keys.forEach { holder.removeState(it) }
                navigator.discarded.removeAll(keys)
            }
        }
    }
    LaunchedEffect(navigator.current.key) {
        UiState.topBarOpacity = 0f
        UiState.topBarTint = Color(0xFF161618)
    }
    Box(modifier.panel(alpha = 0.6f)) {
        AnimatedContent(
            targetState = navigator.current,
            transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
            contentKey = { it.key },
            modifier = Modifier.fillMaxSize(),
        ) { entry ->
            holder.SaveableStateProvider(entry.key) {
                ScreenHost(entry.screen)
            }
        }
        TopBar(Modifier.align(Alignment.TopCenter))
    }
}
