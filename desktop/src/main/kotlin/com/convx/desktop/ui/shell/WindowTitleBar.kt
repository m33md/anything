/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.Log
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame

/**
 * Spotify's window: no Windows title bar, the app's own top bar in its place, and the system's
 * minimise, maximise and close buttons drawn over the top right corner.
 *
 * This takes the JetBrains Runtime's custom title bar, which keeps everything a real title bar
 * does (dragging, double-click to maximise, Snap, the resize border and the shadow). The
 * installer ships that runtime; on any other Java the call is skipped and Windows keeps its own
 * (darkened) title bar above the app, so nothing breaks.
 */
object WindowTitleBar {
    private const val Tag = "TitleBar"

    private var bar: WindowDecorations.CustomTitleBar? = null

    /** The app's display density over Windows' scale; the bar and its insets follow it. */
    private var scale = 1f

    /** Whether the app draws the title bar itself; the top bar then leaves room for the buttons. */
    var active by mutableStateOf(false)
        private set

    /** Width the system buttons take on the right, and anything on the left (none on Windows). */
    var rightInset by mutableStateOf(0.dp)
        private set
    var leftInset by mutableStateOf(0.dp)
        private set

    fun install(frame: Frame, height: Dp) {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        runCatching {
            if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) {
                Log.i(Tag, "Runtime has no custom title bar (${System.getProperty("java.vm.vendor")}); keeping the Windows one")
                return
            }
            val decorations = JBR.getWindowDecorations()
            val titleBar = decorations.createCustomTitleBar()
            // Logical pixels, which are Compose's dp on the desktop.
            titleBar.height = height.value * scale
            titleBar.putProperty("controls.dark", true)
            decorations.setCustomTitleBar(frame, titleBar)
            bar = titleBar
            active = true
            updateInsets()
            Log.i(Tag, "Custom title bar on, buttons take ${titleBar.rightInset}px")
        }.onFailure {
            bar = null
            active = false
            Log.w(Tag, "Couldn't take over the title bar", it)
        }
    }

    /** The buttons' width is known once the window is on screen; call again after it shows. */
    fun updateInsets() {
        val titleBar = bar ?: return
        runCatching {
            rightInset = (titleBar.rightInset / scale).dp
            leftInset = (titleBar.leftInset / scale).dp
        }
    }

    /** Display density changed: the top bar is [height] in the app's dp, so this many of Windows'. */
    fun rescale(scale: Float, height: Dp) {
        this.scale = scale
        val titleBar = bar ?: return
        runCatching { titleBar.height = height.value * scale }
        updateInsets()
    }

    /** True: the pointer is over a control of ours. False: it is over empty title bar. */
    internal fun hitTest(client: Boolean) {
        bar?.forceHitTest(client)
    }
}

/**
 * Makes the empty parts of the top bar behave as the window's title bar: drag to move,
 * double-click to maximise, Snap. Anything that handles a press of its own (buttons, the search
 * field) stays clickable, as in JetBrains' Jewel, which this follows: a press a child consumed is
 * ours, everything else goes to Windows. JBR wants this answered for every mouse event except
 * exits and the wheel.
 */
fun Modifier.windowDragArea(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var inControl = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (!WindowTitleBar.active) continue
            if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) continue
            val consumed = event.changes.any { it.isConsumed }
            if (consumed || inControl) {
                if (event.type == PointerEventType.Press) inControl = true
                if (event.type == PointerEventType.Release) inControl = false
                WindowTitleBar.hitTest(true)
            } else {
                WindowTitleBar.hitTest(false)
            }
        }
    }
}

/** Claims every press inside it, so a control that doesn't consume its own (a text field) still isn't title bar. */
fun Modifier.titleBarClient(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                event.changes.forEach { if (!it.isConsumed) it.consume() }
            }
        }
    }
}
