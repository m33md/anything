/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.convx.desktop.data.Library
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.additiveChrome
import com.convx.desktop.ui.glass.chromeShadow
import com.convx.desktop.ui.glass.elasticStretch
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.onSelected
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.glass.rememberSymbolBounce
import com.convx.desktop.ui.shapes.NavContinuousCapsule
import com.convx.desktop.ui.theme.ConvxFonts
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** The phone's bar circles and search pill: 48 tall. */
val TopBarButtonSize: Dp = 48.dp
private val NavButtonSize: Dp = 40.dp

/**
 * Where the section buttons sit, so a section's strip can unfold out of its button and fold
 * back into it. In root coordinates, which the strip overlay shares.
 */
object TopBarLayout {
    val sectionBounds = mutableStateMapOf<Section, Rect>()
}

private fun Modifier.sectionAnchor(section: Section): Modifier =
    onGloballyPositioned { TopBarLayout.sectionBounds[section] = it.boundsInRoot() }

/**
 * Spotify's top bar in the phone's glass, standing in for the window's title bar: back and
 * forward on the left, the three sections in the middle (Home, the search field with Explore
 * inside it where Spotify keeps Browse, and Library), the account on the right, and the
 * system's window buttons in the corner. Empty space drags the window.
 */
@Composable
fun TopBar(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val screen = navigator.current.screen
    // Detail pages keep the section they were opened from lit, as the phone's tabs do.
    var sticky by remember { mutableStateOf(Section.Home) }
    screen.section()?.let { sticky = it }
    val lit = when (screen) {
        Screen.Search, is Screen.SearchResults, Screen.Settings -> null
        else -> sticky
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .windowDragArea(),
    ) {
        val sides = 14.dp
        val leftWidth = 190.dp
        val rightWidth = WindowTitleBar.rightInset + sides + NavButtonSize * 2 + 8.dp + 12.dp
        // The middle stays centred in the window, as Spotify's does, and gives way on narrow ones.
        val room = maxWidth - 2 * maxOf(leftWidth, rightWidth) - 2 * (TopBarButtonSize + 10.dp)
        val fieldWidth = room.coerceIn(260.dp, 480.dp)

        Row(
            Modifier.align(Alignment.CenterStart).padding(start = sides + WindowTitleBar.leftInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Convx",
                style = ConvxType.Headline.copy(fontFamily = ConvxFonts.Chillax, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = 0.sp),
                color = Color.White,
                modifier = Modifier.padding(top = 2.dp, end = 16.dp),
            )
            NavArrow("chevron_leftpx", "Go back", navigator.canGoBack) { navigator.back() }
            Spacer(Modifier.width(8.dp))
            NavArrow("chevron_right_px", "Go forward", navigator.canGoForward) { navigator.forward() }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionButton(Section.Home, lit == Section.Home) { UiState.openSection(Section.Home, navigator) }
            SearchField(
                width = fieldWidth,
                exploreLit = lit == Section.Explore,
                onExplore = { UiState.openSection(Section.Explore, navigator) },
            )
            SectionButton(Section.Library, lit == Section.Library) { UiState.openSection(Section.Library, navigator) }
        }

        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = sides + WindowTitleBar.rightInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The phone's "Listen Together in top bar": the party, lit while you're in one.
            val party by com.convx.desktop.integrations.party.Party.session.collectAsState()
            GlassCircleButton(
                onClick = { navigator.navigate(Screen.Party) },
                size = NavButtonSize,
                tooltip = party?.let { "Party ${it.code}: ${it.members.size} listening" } ?: "Listen together",
            ) {
                ConvxIcon("group", tint = if (party != null || screen == Screen.Party) LocalAccent.current else GlassTokens.Icon, size = 22.dp)
            }
            Spacer(Modifier.width(8.dp))
            val inSettings = screen == Screen.Settings
            GlassCircleButton(
                onClick = { navigator.navigate(Screen.Settings) },
                size = NavButtonSize,
                tooltip = "Settings and account",
            ) {
                ConvxIcon("account", tint = if (inSettings) LocalAccent.current else GlassTokens.Icon, size = 22.dp)
            }
        }
    }
}

@Composable
private fun SectionButton(section: Section, lit: Boolean, onClick: () -> Unit) {
    val bounce = rememberSymbolBounce().onSelected(lit)
    GlassCircleButton(
        onClick = onClick,
        modifier = Modifier.sectionAnchor(section),
        size = TopBarButtonSize,
        tooltip = section.label,
    ) {
        ConvxIcon(
            section.icon,
            tint = if (lit) LocalAccent.current else GlassTokens.Icon,
            size = 24.dp,
            contentDescription = section.label,
            modifier = bounce.modifier,
        )
    }
}

@Composable
private fun NavArrow(icon: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
    GlassCircleButton(onClick = onClick, size = NavButtonSize, tooltip = tooltip, enabled = enabled) {
        ConvxIcon(icon, tint = GlassTokens.Icon.copy(alpha = if (enabled) GlassTokens.IconDimOpacity else 0.3f), size = 22.dp)
    }
}

/**
 * Spotify's "What do you want to play?" field as the phone's glass search pill. Focusing it
 * opens Search; the button at its end is Explore, where Spotify keeps Browse.
 */
@Composable
private fun SearchField(width: Dp, exploreLit: Boolean, onExplore: () -> Unit) {
    val navigator = LocalNavigator.current
    val focusManager = LocalFocusManager.current
    val glow = rememberInteractiveHighlight()
    val shape = NavContinuousCapsule
    val query = navigator.searchQuery
    val screen = navigator.current.screen
    val searching = screen == Screen.Search || screen is Screen.SearchResults
    var focused by remember { mutableStateOf(false) }

    // An old results page shows what it was a search for. A page opened from the results keeps
    // the search, so going back finds it; going to Home, Explore or Library ends it.
    LaunchedEffect(screen) {
        when {
            screen is Screen.SearchResults -> navigator.searchQuery = screen.query
            screen == Screen.Search -> Unit
            screen.section() != null || screen == Screen.Settings -> {
                navigator.searchQuery = ""
                if (focused) focusManager.clearFocus()
            }
        }
    }

    fun submit() {
        val q = navigator.searchQuery.trim()
        if (q.isEmpty()) return
        Library.addSearch(q)
        navigator.navigate(Screen.SearchResults(q))
    }

    Row(
        Modifier
            .width(width)
            .height(TopBarButtonSize)
            .graphicsLayer(elasticStretch(glow))
            .chromeShadow(shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = GlassTokens.PluckAlphaNeutral)
            .then(glow.modifier)
            .then(glow.gestureModifier)
            .titleBarClient()
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon(
            "search",
            tint = if (searching || focused) Color.White else GlassTokens.Icon,
            size = 22.dp,
            modifier = Modifier.additiveChrome(),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).pointerHoverIcon(PointerIcon.Text), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "What do you want to play?",
                    style = TextStyle(fontFamily = ConvxFonts.SfPro, fontSize = 16.sp),
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { text ->
                    navigator.searchQuery = text
                    val now = navigator.current.screen
                    // Typing a new search shows its suggestions, as Spotify does.
                    if (now != Screen.Search && !(now is Screen.SearchResults && now.query == text)) navigator.navigate(Screen.Search)
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = ConvxFonts.SfPro, fontSize = 16.sp, color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(UiState.searchFocus)
                    .onFocusChanged {
                        focused = it.isFocused
                        UiState.typingFocusChanged("search", it.isFocused)
                        // Spotify opens Search as the field takes the keyboard.
                        if (it.isFocused) {
                            val now = navigator.current.screen
                            if (now != Screen.Search && now !is Screen.SearchResults) navigator.navigate(Screen.Search)
                        }
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
            FieldButton("close", "Clear", GlassTokens.Icon) {
                navigator.searchQuery = ""
                if (navigator.current.screen is Screen.SearchResults) navigator.navigate(Screen.Search)
                runCatching { UiState.searchFocus.requestFocus() }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.18f)))
        val bounce = rememberSymbolBounce().onSelected(exploreLit)
        FieldButton(
            Section.Explore.icon,
            Section.Explore.label,
            if (exploreLit) LocalAccent.current else GlassTokens.Icon,
            modifier = Modifier.sectionAnchor(Section.Explore),
            iconModifier = bounce.modifier,
            onClick = onExplore,
        )
    }
}

/** A round hover target inside the search pill; the glyph is part of the glass. */
@Composable
private fun FieldButton(
    icon: String,
    tooltip: String,
    tint: Color,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.10f else 0f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(icon, tint = tint, size = 21.dp, modifier = iconModifier.additiveChrome())
        }
    }
}
