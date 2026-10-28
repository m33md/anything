/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.Library
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType

/** Back/forward, the search field and settings, over the top of the content panel. */
@Composable
fun TopBar(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val opacity = UiState.topBarOpacity
    Row(
        modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .background(UiState.topBarTint.copy(alpha = 0.94f * opacity))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton("chevron_leftpx", { navigator.back() }, size = 34.dp, iconSize = 22.dp, filled = true, enabled = navigator.canGoBack, tooltip = "Go back")
        Spacer(Modifier.width(8.dp))
        IconButton("chevron_right_px", { navigator.forward() }, size = 34.dp, iconSize = 22.dp, filled = true, enabled = navigator.canGoForward, tooltip = "Go forward")
        Spacer(Modifier.width(16.dp))
        SearchField(Modifier.weight(1f, fill = false).widthIn(max = 440.dp))
        Spacer(Modifier.weight(1f))
        IconButton(
            "settings",
            { navigator.navigate(Screen.Settings) },
            size = 34.dp,
            iconSize = 20.dp,
            filled = true,
            active = navigator.current.screen == Screen.Settings,
            tooltip = "Settings",
        )
    }
}

@Composable
private fun SearchField(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val query = navigator.searchQuery
    val screen = navigator.current.screen

    // Leaving search hands the keyboard back to the app (Space plays again), and coming
    // back to an old results page shows what it was a search for.
    LaunchedEffect(screen) {
        when (screen) {
            is Screen.SearchResults -> navigator.searchQuery = screen.query
            Screen.Search -> Unit
            else -> focusManager.clearFocus()
        }
    }

    fun submit() {
        val q = navigator.searchQuery.trim()
        if (q.isEmpty()) return
        Library.addSearch(q)
        navigator.navigate(Screen.SearchResults(q))
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(ConvxShapes.Capsule)
            .background(Color.White.copy(alpha = if (focused) 0.14f else if (hovered) 0.11f else 0.08f))
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.5f) else Color.Transparent, ConvxShapes.Capsule)
            .hoverable(interaction)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("cosmos_search", tint = if (focused) Color.White else ConvxColors.SecondaryLabel, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("What do you want to play?", style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = { text ->
                    navigator.searchQuery = text
                    val screen = navigator.current.screen
                    if (screen != Screen.Search && screen !is Screen.SearchResults) navigator.navigate(Screen.Search)
                    if (text.isEmpty() && screen is Screen.SearchResults) navigator.navigate(Screen.Search)
                },
                singleLine = true,
                textStyle = ConvxType.Body.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(UiState.searchFocus)
                    .onFocusChanged { state ->
                        val screen = navigator.current.screen
                        if (state.isFocused && screen != Screen.Search && screen !is Screen.SearchResults) navigator.navigate(Screen.Search)
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                submit()
                                true
                            }
                            Key.Escape -> {
                                focusManager.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(24.dp).pressable({
                    navigator.searchQuery = ""
                    if (navigator.current.screen is Screen.SearchResults) navigator.navigate(Screen.Search)
                }),
                contentAlignment = Alignment.Center,
            ) { ConvxIcon("close", tint = ConvxColors.SecondaryLabel, size = 18.dp) }
        }
    }
}
