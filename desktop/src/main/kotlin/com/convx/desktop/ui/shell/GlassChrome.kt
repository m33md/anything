/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.Library
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.InteractiveHighlight
import com.convx.desktop.ui.glass.additiveChrome
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.theme.ConvxFonts
import com.convx.desktop.ui.theme.LocalAccent

/** The phone's search pill: 48 tall. */
val SearchPillHeight: Dp = 48.dp

/**
 * The phone's NavBarSearchInputBar: in tab view the now playing pill turns into the search
 * field while you search, a glass capsule with a back arrow, the field and a clear button.
 */
@Composable
fun SearchPill(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val glow = remember(scope) { InteractiveHighlight(scope) }
    val query = navigator.searchQuery
    val screen = navigator.current.screen
    val shape = GlassShapes.Capsule
    val label = Color.White
    val icon = GlassTokens.Icon

    // Coming back to an old results page shows what it was a search for.
    LaunchedEffect(screen) {
        if (screen is Screen.SearchResults) navigator.searchQuery = screen.query
    }
    // The pill mounts as you enter search; take the keyboard, as the phone does once it lands.
    LaunchedEffect(Unit) { runCatching { UiState.searchFocus.requestFocus() } }

    fun submit() {
        val q = navigator.searchQuery.trim()
        if (q.isEmpty()) return
        Library.addSearch(q)
        navigator.navigate(Screen.SearchResults(q))
    }

    Row(
        modifier
            .height(SearchPillHeight)
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = GlassTokens.PluckAlphaNeutral)
            .then(glow.modifier)
            .then(glow.gestureModifier)
            .padding(start = 11.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIcon("arrow_back", "Leave search", icon) {
            focusManager.clearFocus()
            navigator.searchQuery = ""
            navigator.openRoot(Screen.Home)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search YouTube Music…",
                    style = TextStyle(fontFamily = ConvxFonts.SfPro, fontSize = 17.sp),
                    color = label.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { text ->
                    navigator.searchQuery = text
                    if (text.isEmpty() && navigator.current.screen is Screen.SearchResults) navigator.navigate(Screen.Search)
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = ConvxFonts.SfPro, fontSize = 17.sp, color = label),
                cursorBrush = SolidColor(label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(UiState.searchFocus)
                    .onFocusChanged { UiState.typingFocusChanged("search", it.isFocused) }
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
            PillIcon("close", "Clear", icon) {
                navigator.searchQuery = ""
                if (navigator.current.screen is Screen.SearchResults) navigator.navigate(Screen.Search)
                runCatching { UiState.searchFocus.requestFocus() }
            }
        }
    }
}

@Composable
private fun PillIcon(icon: String, tooltip: String, tint: Color, size: Dp = 40.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered.value) 0.10f else 0f))
                .hoverable(interaction)
                .pressable(onClick, interaction = interaction, pressScale = 0.86f),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(icon, tint = tint, size = 22.dp, modifier = Modifier.additiveChrome())
        }
    }
}

/**
 * Back and forward on one small glass capsule at the top of the page: on the phone each
 * screen floats its own glass back button; a desktop also wants forward.
 */
@Composable
fun NavCapsule(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val shape = GlassShapes.Capsule
    Row(
        modifier
            .height(40.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = 0.3f)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow("chevron_leftpx", "Go back", navigator.canGoBack) { navigator.back() }
        NavArrow("chevron_right_px", "Go forward", navigator.canGoForward) { navigator.forward() }
    }
}

@Composable
private fun NavArrow(icon: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered.value && enabled) 0.10f else 0f))
                .hoverable(interaction)
                .pressable(onClick, enabled = enabled, interaction = interaction, pressScale = 0.86f),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(
                icon,
                tint = GlassTokens.Icon.copy(alpha = if (enabled) GlassTokens.IconDimOpacity else 0.3f),
                size = 22.dp,
                modifier = Modifier.additiveChrome(),
            )
        }
    }
}

/**
 * The phone's AppTabViewTopPill: on the artist page the side bar folds into one glass
 * capsule at the top, with a button to open the side bar, the tabs, and search.
 */
@Composable
fun TabTopPill(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val shape = GlassShapes.Capsule
    val chrome = Color.White
    val accent = LocalAccent.current
    val selected = navigator.current.screen.tab()
    Row(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = 0.3f)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIcon("sidebar", "Show side bar", chrome) { UiState.topNavMenuOpen = true }
        SideTabs.filter { it.screen != Screen.Search }.forEach { tab ->
            val isSelected = tab.screen == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered = interaction.collectIsHoveredAsState()
            Row(
                Modifier
                    .height(40.dp)
                    .clip(GlassShapes.Capsule)
                    .background(
                        when {
                            isSelected -> chrome.copy(alpha = 0.14f)
                            hovered.value -> chrome.copy(alpha = 0.07f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .pressable({ navigator.openRoot(tab.screen) }, interaction = interaction, pressScale = 0.96f)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tab.label,
                    style = TextStyle(
                        fontFamily = ConvxFonts.SfPro,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = (-0.43).sp,
                    ),
                    color = if (isSelected) chrome else chrome.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.2f))
                .pressable({
                    navigator.openRoot(Screen.Search)
                }, pressScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon("search", tint = accent, size = 18.dp)
        }
    }
}
