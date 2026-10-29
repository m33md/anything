/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.screens.HomeFeed
import com.convx.desktop.ui.screens.HomeSection
import com.convx.desktop.ui.screens.QuickPicksLastListen
import com.convx.desktop.ui.theme.ConvxColors

/** Where Convx opens, by the phone's names for its tabs. */
private val OpenTabs = listOf("home" to "Home", "search" to "Search", "library" to "Library")

/**
 * The phone's Layout & home settings that mean something on a PC: the tab Convx opens on, the
 * featured card, and the Home feed's order and filters. Its app language, density and swipe
 * gestures aren't here: Convx on the PC is in English only and has no swipes.
 */
@Composable
fun LayoutHomeSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("More", footer = "Takes effect the next time you open Convx.") {
        ChoiceRow("Default open tab", options = OpenTabs, selected = settings.defaultTab, icon = "sidebar", tint = SystemColors.Blue) { tab ->
            AppSettings.update { it.copy(defaultTab = tab) }
        }
    }
    SectionGap()
    SettingsSection("Home") {
        SwitchRow("Hero card", "Show the large featured card at the top of Home", settings.homeHeroCard, icon = "star", tint = SystemColors.Orange) { on ->
            AppSettings.update { it.copy(homeHeroCard = on) }
        }
        RowDivider()
        SwitchRow("Mood filters", "Show Energize, Relax and other filters at the top of Home", settings.homeMoodFilters, icon = "tune", tint = SystemColors.Purple) { on ->
            AppSettings.update { it.copy(homeMoodFilters = on) }
        }
        RowDivider()
        ChoiceRow(
            "Set quick picks",
            options = listOf("quick_picks" to "Quick picks", QuickPicksLastListen to "Based on last song listened"),
            selected = settings.quickPicksMode,
            icon = "queue_music",
            tint = SystemColors.Yellow,
        ) { mode ->
            AppSettings.update { it.copy(quickPicksMode = mode) }
            HomeFeed.load(force = true)
        }
    }
    SectionGap()
    SettingsSection("Home feed") {
        SwitchRow(
            "Randomize Home Screen Order",
            "Randomly reorder home screen sections with weighted priorities",
            settings.homeRandomOrder,
            icon = "shuffle",
            tint = SystemColors.Pink,
        ) { on -> AppSettings.update { it.copy(homeRandomOrder = on) } }
        RowDivider()
        LinkRow(
            "Arrange home feed (Beta)",
            "Drag to choose the order sections appear in",
            icon = "list",
            tint = SystemColors.Orange,
        ) { SettingsNav.push(SettingsPage.HomeFeedOrder) }
    }
}

private val OrderRowHeight = 52.dp

/**
 * The phone's Arrange home feed: drag the sections into the order you want, switch off the ones
 * you don't. Arranging turns shuffling off, as it does there. Similar to and YouTube's own shelves
 * aren't listed, because they change with every refresh; they follow whatever is arranged here.
 */
@Composable
fun HomeFeedOrderSettings() {
    val settings by AppSettings.state.collectAsState()
    val names = remember { HomeSection.arrangeable.toMap() }
    val order = remember { mutableStateListOf<String>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    fun saved(order: List<String>) =
        order.filter { it in names } + HomeSection.arrangeable.map { it.first }.filterNot { it in order }
    // Follows Settings, except while a row is held, so a drag isn't undone under the pointer.
    LaunchedEffect(settings.homeSectionOrder, dragging) {
        if (dragging == null) {
            val ids = saved(settings.homeSectionOrder.distinct())
            if (order.toList() != ids) {
                order.clear()
                order.addAll(ids)
            }
        }
    }
    val rowPx = with(LocalDensity.current) { OrderRowHeight.toPx() }
    fun drop() {
        if (dragging != null && order.toList() != saved(AppSettings.value.homeSectionOrder.distinct())) {
            AppSettings.update { it.copy(homeSectionOrder = order.toList(), homeRandomOrder = false) }
        }
        dragging = null
        offset = 0f
    }
    SettingsSection(
        footer = "Drag to choose the order sections appear in. Sections that come and go with your recommendations always follow the ones you arrange here.",
    ) {
        Column(
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        // The switch keeps its own clicks; anywhere else on a row picks it up.
                        dragging = if (at.x < size.width - 80.dp.toPx()) order.getOrNull((at.y / rowPx).toInt()) else null
                        offset = 0f
                    },
                    onDragEnd = { drop() },
                    onDragCancel = { drop() },
                ) { change, amount ->
                    val id = dragging ?: return@detectDragGestures
                    change.consume()
                    offset += amount.y
                    val from = order.indexOf(id)
                    if (offset > rowPx / 2 && from < order.lastIndex) {
                        order.add(from + 1, order.removeAt(from))
                        offset -= rowPx
                    } else if (offset < -rowPx / 2 && from > 0) {
                        order.add(from - 1, order.removeAt(from))
                        offset += rowPx
                    }
                }
            },
        ) {
            order.forEachIndexed { index, id ->
                key(id) {
                    val held = dragging == id
                    // A hidden section keeps its place, so switching it back on returns it there.
                    val shown = id !in settings.homeSectionsHidden
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(OrderRowHeight)
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (held) offset else 0f
                                shadowElevation = if (held) 16.dp.toPx() else 0f
                            }
                            .background(if (held) ConvxColors.Card else Color.Transparent),
                    ) {
                        SettingsRow(
                            names[id] ?: id,
                            leading = { ConvxIcon("drag_handle", tint = ConvxColors.TertiaryLabel, size = 20.dp) },
                            titleColor = if (shown) ConvxColors.Label else ConvxColors.SecondaryLabel,
                            onClick = { toggleSection(id) },
                        ) {
                            GlassSwitch(shown, { toggleSection(id) })
                        }
                        if (index < order.lastIndex && !held) {
                            Box(Modifier.align(Alignment.BottomStart)) { RowDivider(48.dp) }
                        }
                    }
                }
            }
        }
    }
    SectionGap()
    SettingsSection {
        // An empty order is what brings back the default one, as on the phone.
        ButtonRow("Reset to default order") { AppSettings.update { it.copy(homeSectionOrder = emptyList()) } }
    }
}

private fun toggleSection(id: String) = AppSettings.update { s ->
    s.copy(homeSectionsHidden = if (id in s.homeSectionsHidden) s.homeSectionsHidden - id else s.homeSectionsHidden + id)
}
