/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.OverlayHost
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PageInsets
import com.convx.desktop.ui.components.Playback
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.glass.LocalAppBackdrop
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.player.FullPlayer
import com.convx.desktop.ui.player.MeshBackground
import com.convx.desktop.ui.player.NowPlayingPill
import com.convx.desktop.ui.player.NowPlayingPillHeight
import com.convx.desktop.ui.player.NowPlayingPillWidthFraction
import com.convx.desktop.ui.screens.ScreenHost
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Below this window width the side bar folds to its icon rail on its own. */
private val AutoCollapseWidth = 1100.dp

/** Space the pages leave under the bottom pill: the pill and its margins. */
private val BottomChromeInset = NowPlayingPillHeight + 24.dp

/**
 * The window, laid out as the phone lays itself out on a big screen (its tab view): the
 * pages fill the whole window on true black, and the chrome floats over them as liquid
 * glass that refracts them: the side bar on the left, the now playing pill at the bottom
 * (the search field while you search), back and forward at the top. On the artist page
 * the side bar folds into a pill at the top so the page keeps the whole width.
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

    // Look the lyrics up as a song starts, and the next one's too, so they are there on open.
    LaunchedEffect(player.current?.id) {
        player.current?.let(LyricsService::prefetch)
        player.queue.getOrNull(player.index + 1)?.let(LyricsService::prefetch)
    }

    val screen = navigator.current.screen
    LaunchedEffect(screen) { UiState.topNavMenuOpen = false }

    // Everything the glass refracts: the pages over the phone's base fill.
    val appBackdrop = rememberLayerBackdrop(
        onDraw = remember {
            {
                drawRect(Color.Black)
                drawContent()
            }
        },
    )

    ConvxTheme(accent) {
        CompositionLocalProvider(LocalNavigator provides navigator, LocalPlayback provides playback, LocalMenuBackdrop provides appBackdrop) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val collapsed = settings.sidebarCollapsed || maxWidth < AutoCollapseWidth
                val topNavigation = screen is Screen.Artist && !UiState.topNavMenuOpen
                val searching = screen == Screen.Search || screen is Screen.SearchResults
                val showPill = player.current != null || searching
                // A spring overshoots past its target; an inset below zero would be negative padding.
                val startInsetRaw by animateDpAsState(
                    when {
                        screen is Screen.Artist -> 0.dp
                        collapsed -> SideBarCollapsedContentInset
                        else -> SideBarContentInset
                    },
                    spring(0.9f, 400f),
                )
                val startInset = startInsetRaw.coerceAtLeast(0.dp)
                val insets = PageInsets(start = startInset, bottom = if (showPill) BottomChromeInset else 0.dp)

                // The pages, recorded as the backdrop every glass surface samples.
                Box(Modifier.fillMaxSize().layerBackdrop(appBackdrop)) {
                    if (settings.ambientWindow) {
                        val mesh by rememberMeshColors(artworkUrl(player.current?.thumbnail, 226))
                        MeshBackground(mesh, player.current?.id, scrim = 0.78f)
                    }
                    CompositionLocalProvider(LocalPageInsets provides insets) {
                        Pages(navigator)
                    }
                }

                // The chrome samples the recording, so it sits outside it.
                CompositionLocalProvider(LocalAppBackdrop provides appBackdrop) {
                    AnimatedContent(
                        targetState = topNavigation,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        modifier = Modifier.fillMaxSize(),
                    ) { asPill ->
                        Box(Modifier.fillMaxSize()) {
                            if (asPill) {
                                TabTopPill(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                            } else {
                                FloatingSideBar(
                                    collapsed = collapsed && screen !is Screen.Artist,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = SideBarMargin, top = SideBarMargin, bottom = SideBarMargin),
                                )
                            }
                        }
                    }

                    // Beside the side bar; on the artist page the opened side bar overlays the page.
                    val navStart by animateDpAsState(
                        when {
                            topNavigation -> 16.dp
                            screen is Screen.Artist -> SideBarContentInset + 8.dp
                            else -> startInset + 16.dp
                        },
                        spring(0.9f, 400f),
                    )
                    NavCapsule(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = navStart.coerceAtLeast(0.dp), top = 12.dp),
                    )

                    // Centred in the content beside the side bar, not in the window.
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = startInset)) {
                        val pillWidth = (maxWidth * NowPlayingPillWidthFraction).coerceAtMost(1040.dp)
                        AnimatedContent(
                            targetState = if (searching) 1 else if (player.current != null) 2 else 0,
                            transitionSpec = {
                                // The phone's tabViewSearchPill: in on a bouncy spring, out flat.
                                (
                                    slideInVertically(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) { if (targetState == 1) it / 2 else it } +
                                        fadeIn()
                                    ) togetherWith (
                                    slideOutVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) { if (targetState == 1) it else it / 2 } +
                                        fadeOut()
                                    )
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        ) { which ->
                            when (which) {
                                1 -> SearchPill(Modifier.width(pillWidth))
                                2 -> NowPlayingPill(Modifier.width(pillWidth))
                                else -> Unit
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = UiState.fullPlayer,
                    enter = slideInVertically { it / 6 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.98f),
                    exit = slideOutVertically { it / 6 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.98f),
                ) {
                    // Menus over the player would refract the pages hidden under it.
                    CompositionLocalProvider(LocalMenuBackdrop provides null) { FullPlayer() }
                }

                OverlayHost()
            }
        }
    }
}

@Composable
private fun Pages(navigator: Navigator) {
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
}
