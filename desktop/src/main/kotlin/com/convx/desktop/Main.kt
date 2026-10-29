/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.DiscordPresence
import com.convx.desktop.integrations.LastFm
import com.convx.desktop.integrations.Scrobbler
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.AudioEngine
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.AppShell
import com.convx.desktop.ui.shell.Navigator
import com.convx.desktop.ui.shell.TopBarHeight
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.shell.WindowTitleBar
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Window as AwtWindow

@OptIn(FlowPreview::class)
fun main() {
    Log.i("Startup", "Convx ${AppInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> Log.e("Crash", "Uncaught on ${thread.name}", error) }

    AppScope.launch(Dispatchers.IO) { initYouTube() }
    AppScope.launch(Dispatchers.IO) {
        AudioCache.cleanupPartials()
        AudioEngine.warmUp()
    }
    PlayerController.restore()
    DiscordPresence.start()
    SpotifyAccount.restore()
    AppScope.launch(Dispatchers.IO) { LastFm.restore() }
    Scrobbler.start()

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("convx.png")
        ?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

    application {
        val saved = remember { AppSettings.value }
        val windowState = rememberWindowState(
            placement = if (saved.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center),
            size = DpSize(saved.windowWidth.coerceAtLeast(940).dp, saved.windowHeight.coerceAtLeast(600).dp),
        )
        val navigator = remember { Navigator() }

        fun quit() {
            DiscordPresence.shutdown()
            PlayerController.shutdown()
            Library.flush()
            SpotifyMatcher.flush()
            AppSettings.flush()
            exitApplication()
        }

        Window(
            onCloseRequest = ::quit,
            state = windowState,
            title = "Convx",
            icon = icon,
            onPreviewKeyEvent = { shortcut(it, navigator) },
            onKeyEvent = { event ->
                // A text field takes the typed space but lets the key press through, so check.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar && !event.isCtrlPressed && UiState.typingIn == null) {
                    PlayerController.togglePlay()
                    true
                } else {
                    false
                }
            },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(940, 600)
                window.background = java.awt.Color.BLACK
                WindowsChrome.darken(window)
                // Spotify's window: the top bar is the title bar. Falls back to the dark Windows one.
                WindowTitleBar.install(window, TopBarHeight)
                window.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) = WindowTitleBar.updateInsets()
                    override fun componentShown(e: ComponentEvent?) = WindowTitleBar.updateInsets()
                })
                // The buttons' width settles once the frame is on screen.
                repeat(10) {
                    delay(100)
                    WindowTitleBar.updateInsets()
                }
            }
            LaunchedEffect(windowState) {
                snapshotFlow { Triple(windowState.size, windowState.placement, Unit) }
                    .debounce(600)
                    .collect { (size, placement) ->
                        AppSettings.update {
                            if (placement == WindowPlacement.Maximized) {
                                it.copy(windowMaximized = true)
                            } else {
                                it.copy(
                                    windowMaximized = false,
                                    windowWidth = size.width.value.toInt(),
                                    windowHeight = size.height.value.toInt(),
                                )
                            }
                        }
                    }
            }
            AppShell(navigator)
        }
    }
}

/** Window-wide shortcuts, Spotify's set where it has one. */
private fun shortcut(event: KeyEvent, navigator: Navigator): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed
    return when {
        event.key == Key.Escape && UiState.fullPlayer -> {
            UiState.fullPlayer = false
            true
        }
        event.key == Key.F11 -> {
            UiState.fullPlayer = !UiState.fullPlayer
            true
        }
        event.key == Key.MediaPlayPause -> {
            PlayerController.togglePlay()
            true
        }
        event.key == Key.MediaNext -> {
            PlayerController.next()
            true
        }
        event.key == Key.MediaPrevious -> {
            PlayerController.previous()
            true
        }
        ctrl && (event.key == Key.L || event.key == Key.K) -> {
            UiState.fullPlayer = false
            // The top bar's field opens Search as it takes the keyboard.
            runCatching { UiState.searchFocus.requestFocus() }
            true
        }
        ctrl && event.key == Key.DirectionRight -> {
            if (event.isShiftPressed) PlayerController.seekBy(10_000) else PlayerController.next()
            true
        }
        ctrl && event.key == Key.DirectionLeft -> {
            if (event.isShiftPressed) PlayerController.seekBy(-10_000) else PlayerController.previous()
            true
        }
        ctrl && event.key == Key.DirectionUp -> {
            PlayerController.setVolume((PlayerController.state.value.volume + 0.1f).coerceAtMost(1f))
            true
        }
        ctrl && event.key == Key.DirectionDown -> {
            PlayerController.setVolume((PlayerController.state.value.volume - 0.1f).coerceAtLeast(0f))
            true
        }
        ctrl && event.key == Key.S -> {
            PlayerController.toggleShuffle()
            true
        }
        ctrl && event.key == Key.R -> {
            PlayerController.cycleRepeat()
            true
        }
        event.isAltPressed && event.key == Key.DirectionLeft -> {
            navigator.back()
            true
        }
        event.isAltPressed && event.key == Key.DirectionRight -> {
            navigator.forward()
            true
        }
        else -> false
    }
}

/** Windows 10/11: a dark title bar that matches the app instead of a white strip. */
private object WindowsChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    private interface Dwm : JnaLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("win")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    fun darken(window: AwtWindow) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1).pointer, 4)
            // COLORREF is 0x00BBGGRR. Windows 10 ignores these two; 11 paints the caption to match.
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(0x000000).pointer, 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(0xFFFFFF).pointer, 4)
        }.onFailure { Log.w("Window", "Couldn't darken the title bar", it) }
    }
}
