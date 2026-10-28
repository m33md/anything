/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.glass.BarMotion
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.additiveChrome
import com.convx.desktop.ui.glass.chromeShadow
import com.convx.desktop.ui.glass.elasticStretch
import com.convx.desktop.ui.glass.glassPuck
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.shapes.NavContinuousCapsule
import com.convx.desktop.ui.theme.ConvxFonts
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The strip's own height, and the space it takes from the pages when open. */
val SectionStripHeight: Dp = 44.dp
private val StripGap = 6.dp
val SectionStripRoom: Dp = StripGap + SectionStripHeight + 10.dp

private val StripPadding = 4.dp
private val ChipHeight = 36.dp
private val StripMargin = 12.dp

/** The phone side bar's puck: #333 at 80%. */
private val PuckWash = Color(0xFF333333).copy(alpha = 0.80f)

/**
 * The fold, as the phone's bottom bar folds its tabs into one circle and back: the centre
 * travels on a critically damped curve, the body grows on a spring that swells a few percent
 * past its size, and the width on a bouncier one. One clock per part, all ending together.
 */
@Stable
class StripMotion {
    val travel = Animatable(0f)
    val body = Animatable(0f)
    val width = Animatable(0f)

    /** How open the strip is, without its overshoot: the room the pages make for it. */
    val room: Float get() = body.value.coerceIn(0f, 1f)

    val folded: Boolean get() = travel.value <= 0.001f && body.value <= 0.001f
}

@Composable
fun rememberStripMotion(open: Boolean): StripMotion {
    val motion = remember { StripMotion() }
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        val ms = if (open) BarMotion.ExpandMs else BarMotion.CollapseMs
        launch { motion.travel.animateTo(target, tween(ms, easing = BarMotion.Travel)) }
        launch { motion.body.animateTo(target, tween(ms, easing = if (open) BarMotion.SurfaceExpand else BarMotion.FoldCollapse)) }
        launch { motion.width.animateTo(target, tween(ms, easing = if (open) BarMotion.SurfaceExpandWidth else BarMotion.FoldCollapse)) }
    }
    return motion
}

@Immutable
private data class StripEntry(
    val key: String,
    val label: String,
    val icon: String?,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * A section's own places, unfolded out of its button in the top bar: Home's moods, Explore's
 * shelves, the Library's views. One glass capsule with a glass puck under the one you're on.
 */
@Composable
fun SectionStrip(motion: StripMotion, open: Section?, startInset: Dp, modifier: Modifier = Modifier) {
    // The strip keeps drawing the section it folds away from.
    var shown by remember { mutableStateOf(open ?: Section.Home) }
    if (open != null) shown = open
    if (open == null && motion.folded) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val entries = rememberStripEntries(shown)
        var contentWidth by remember(shown) { mutableIntStateOf(0) }
        val px = { dp: Dp -> with(density) { dp.toPx() } }
        val windowWidth = constraints.maxWidth.toFloat()
        val maxWidth = (windowWidth - px(startInset) - 2 * px(StripMargin)).coerceAtLeast(px(160.dp))
        val target = (contentWidth + 2 * px(StripPadding)).coerceAtMost(maxWidth)

        // Content that changes while open (moods arriving, another section) springs to its width.
        val openWidth = remember { Animatable(0f) }
        LaunchedEffect(target, contentWidth > 0) {
            if (contentWidth <= 0) return@LaunchedEffect
            if (motion.folded || openWidth.value == 0f) openWidth.snapTo(target) else openWidth.animateTo(target, spring(0.62f, 380f))
        }

        val button = TopBarLayout.sectionBounds[shown]
            ?: Rect(Offset(windowWidth / 2f - px(24.dp), px(8.dp)), androidx.compose.ui.geometry.Size(px(48.dp), px(48.dp)))
        val circle = button.height
        val stripHeight = px(SectionStripHeight)
        val w = openWidth.value.coerceAtLeast(circle)
        val openCenterX = (windowWidth / 2f).coerceIn(px(startInset) + px(StripMargin) + w / 2f, windowWidth - px(StripMargin) - w / 2f)
        val openCenterY = px(TopBarHeight) + px(StripGap) + stripHeight / 2f

        val t = motion.travel.value
        // Sizes are clamped, positions are not: the travel never overshoots anyway.
        val width = lerp(circle, w, motion.width.value).coerceAtLeast(circle * 0.7f)
        val height = lerp(circle, stripHeight, motion.body.value).coerceAtLeast(circle * 0.7f)
        val centerX = lerp(button.center.x, openCenterX, t)
        val centerY = lerp(button.center.y, openCenterY, t)
        val contentAlpha = ((motion.body.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
        // The width's swell mid-flight would reach over Your Library; keep it to the page's side.
        val minLeft = px(startInset) + px(StripMargin)
        val maxRight = windowWidth - px(StripMargin)
        val left = (centerX - width / 2f).let { if (width <= maxRight - minLeft) it.coerceIn(minLeft, maxRight - width) else it }

        val glow = rememberInteractiveHighlight()
        val shape = NavContinuousCapsule
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), (centerY - height / 2f).roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .graphicsLayer(elasticStretch(glow))
                // Out of sight behind its button until it starts to leave it.
                .graphicsLayer { alpha = (t * 6f).coerceIn(0f, 1f) }
                .chromeShadow(shape)
                .clip(shape)
                .liquidGlass(shape, highlightAlpha = GlassTokens.PluckAlphaNeutral)
                .then(glow.modifier)
                .then(glow.gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            // Laid out at its open width and cut by the growing glass, as the phone clips its tabs.
            Box(
                Modifier
                    .requiredWidth(with(density) { (w - 2 * px(StripPadding)).coerceAtLeast(0f).toDp() })
                    .fillMaxHeight()
                    .graphicsLayer { alpha = contentAlpha },
                contentAlignment = Alignment.CenterStart,
            ) {
                StripChips(entries, folded = motion.folded, onContentWidth = { contentWidth = it })
            }
        }
    }
}

@Composable
private fun StripChips(entries: List<StripEntry>, folded: Boolean, onContentWidth: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val bounds = remember(entries.map { it.key }) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val selected = entries.indexOfFirst { it.selected }
    val puckX = remember { Animatable(0f) }
    val puckW = remember { Animatable(0f) }
    val target = bounds[selected]
    LaunchedEffect(selected, target, folded) {
        val (x, w) = target ?: return@LaunchedEffect
        if (folded || puckW.value == 0f) {
            puckX.snapTo(x)
            puckW.snapTo(w)
        } else {
            // The phone bar's underdamped puck spring.
            launch { puckX.animateTo(x, spring(0.6f, 380f)) }
            launch { puckW.animateTo(w, spring(0.6f, 380f)) }
        }
        val viewport = scroll.viewportSize
        if (viewport > 0 && (x < scroll.value || x + w > scroll.value + viewport)) {
            scroll.animateScrollTo((x + w / 2f - viewport / 2f).roundToInt().coerceIn(0, scroll.maxValue))
        }
    }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().horizontalScroll(scroll), contentAlignment = Alignment.CenterStart) {
        if (selected >= 0 && puckW.value > 0f) {
            Box(
                Modifier
                    .offset { IntOffset(puckX.value.roundToInt(), 0) }
                    .width(with(density) { puckW.value.toDp() })
                    .height(ChipHeight)
                    .glassPuck(GlassShapes.Capsule, blurRadius = GlassConfig.Default.blurRadius.dp, wash = PuckWash),
            )
        }
        Row(Modifier.onSizeChanged { onContentWidth(it.width) }, verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { index, entry ->
                StripChip(
                    entry,
                    Modifier.onPlaced { bounds[index] = it.positionInParent().x to it.size.width.toFloat() },
                )
            }
        }
    }
}

@Composable
private fun StripChip(entry: StripEntry, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink = if (entry.selected) accent else GlassTokens.Title.copy(alpha = 0.85f)
    Row(
        modifier
            .height(ChipHeight)
            .clip(GlassShapes.Capsule)
            .background(if (hovered && !entry.selected) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = entry.onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.icon != null) {
            ConvxIcon(entry.icon, tint = ink, size = 18.dp, modifier = Modifier.additiveChrome(!entry.selected))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            entry.label,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = ConvxFonts.SfPro,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = (-0.23).sp,
            ),
            color = ink,
            maxLines = 1,
            modifier = Modifier.additiveChrome(!entry.selected),
        )
    }
}

@Composable
private fun rememberStripEntries(section: Section): List<StripEntry> {
    val navigator = LocalNavigator.current
    val screen = navigator.current.screen
    return when (section) {
        Section.Home -> {
            fun pick(chip: String?) {
                UiState.homeChip = chip
                if (navigator.current.screen != Screen.Home) navigator.switchTo(Screen.Home)
            }
            buildList {
                add(StripEntry("all", "All", null, UiState.homeChip == null) { pick(null) })
                UiState.homeChips.forEach { title ->
                    add(StripEntry("chip:$title", title, null, UiState.homeChip == title) { pick(title) })
                }
            }
        }
        // A shelf that didn't come back (Charts isn't offered everywhere) gets no chip.
        Section.Explore -> ExploreAnchor.entries.filter { UiState.exploreAnchors?.contains(it) != false }.map { anchor ->
            StripEntry(anchor.name, anchor.label, anchor.icon, UiState.exploreInView == anchor) {
                if (navigator.current.screen != Screen.Explore) navigator.switchTo(Screen.Explore)
                UiState.exploreJump = anchor
            }
        }
        Section.Library -> {
            fun openTab(tab: LibraryTab) {
                UiState.libraryTab = tab
                navigator.switchTo(Screen.Library)
            }
            val current = UiState.libraryTab
            fun on(tab: LibraryTab) = screen == Screen.Library && current == tab
            listOf(
                StripEntry("recent", LibraryTab.All.label, "music_history", on(LibraryTab.All)) { openTab(LibraryTab.All) },
                StripEntry("playlists", LibraryTab.Playlists.label, "playlist_play", on(LibraryTab.Playlists)) { openTab(LibraryTab.Playlists) },
                StripEntry("albums", LibraryTab.Albums.label, "album", on(LibraryTab.Albums)) { openTab(LibraryTab.Albums) },
                StripEntry("artists", LibraryTab.Artists.label, "artist", on(LibraryTab.Artists)) { openTab(LibraryTab.Artists) },
                StripEntry("songs", "Songs", "music_note", screen == Screen.Liked) { navigator.switchTo(Screen.Liked) },
                StripEntry("history", "History", "history", screen == Screen.History) { navigator.switchTo(Screen.History) },
                StripEntry("stats", "Stats", "stats", screen == Screen.TopSongs) { navigator.switchTo(Screen.TopSongs) },
            )
        }
    }
}
