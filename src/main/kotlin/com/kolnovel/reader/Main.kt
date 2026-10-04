package com.kolnovel.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kolnovel.reader.data.SettingsStore
import com.kolnovel.reader.ui.App
import com.kolnovel.reader.ui.AppServices
import com.kolnovel.reader.ui.handleGlobalKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.res.painterResource

@OptIn(FlowPreview::class)
fun main() = application {
    val saved = SettingsStore.settings
    val state = rememberWindowState(
        placement = if (saved.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (saved.windowX >= 0) WindowPosition(saved.windowX.dp, saved.windowY.dp) else WindowPosition.PlatformDefault,
        size = DpSize(saved.windowW.dp, saved.windowH.dp),
    )
    val services = remember { AppServices() }
    LaunchedEffect(state) {
        // Opens the same size and place next time.
        snapshotFlow { Triple(state.placement, state.position, state.size) }.debounce(800).collect { (placement, pos, size) ->
            SettingsStore.update {
                val maximized = placement == WindowPlacement.Maximized
                if (maximized || pos !is WindowPosition.Absolute) it.copy(windowMaximized = maximized)
                else it.copy(
                    windowMaximized = false,
                    windowX = pos.x.value.toInt(), windowY = pos.y.value.toInt(),
                    windowW = size.width.value.toInt(), windowH = size.height.value.toInt(),
                )
            }
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "ملوك الروايات - قارئ",
        icon = painterResource("app_icon.png"),
        onPreviewKeyEvent = { handleGlobalKey(services, it) },
    ) {
        App(services)
    }
}
