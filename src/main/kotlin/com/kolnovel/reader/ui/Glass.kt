package com.kolnovel.reader.ui

import kotlin.math.roundToInt
import com.kolnovel.reader.data.LibraryEntry
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelSummary
import kotlin.math.cos
import kotlin.math.sin

/**
 * Frosted glass: a see-through tint with a light top edge and a darker bottom edge.
 * It looks like glass because the app background behind it has soft moving color glows.
 */
fun Modifier.glass(colors: AppColors, shape: Shape = RoundedCornerShape(18.dp), strong: Boolean = false): Modifier {
    val fill = if (strong) colors.glassFillStrong else colors.glassFill
    return this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    lerpColor(fill, Color.White.copy(alpha = fill.alpha), if (colors.dark) 0.10f else 0.35f),
                    fill,
                    fill.copy(alpha = fill.alpha * 0.75f),
                ),
                start = Offset.Zero,
                end = Offset(0f, Float.POSITIVE_INFINITY),
            )
        )
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(colors.glassEdge, colors.glassEdge.copy(alpha = colors.glassEdge.alpha * 0.3f), colors.glassShadowEdge),
                start = Offset.Zero,
                end = Offset(0f, Float.POSITIVE_INFINITY),
            ),
            shape,
        )
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    strong: Boolean = false,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    Column(modifier.glass(colors, shape, strong).padding(padding), content = content)
}

/** Slow drifting accent-colored glows behind everything, so the glass has something to frost. */
@Composable
fun LiveBackground(enabled: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = LocalAppColors.current
    val phase = if (enabled) {
        val transition = rememberInfiniteTransition()
        val p by transition.animateFloat(
            0f, (2 * Math.PI).toFloat(),
            infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart),
        )
        p
    } else 0.8f
    val glowA = colors.accent.copy(alpha = if (colors.dark) 0.30f else 0.22f)
    val glowB = lerpColor(colors.accent, if (colors.dark) Color(0xFF3355FF) else Color(0xFF66AAFF), 0.55f)
        .copy(alpha = if (colors.dark) 0.18f else 0.16f)
    Box(
        modifier
            .background(colors.background)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.55f
                val c1 = Offset(w * (0.80f + 0.12f * cos(phase)), h * (0.15f + 0.10f * sin(phase)))
                val c2 = Offset(w * (0.15f + 0.10f * sin(phase * 1.3f)), h * (0.85f + 0.08f * cos(phase)))
                drawCircle(Brush.radialGradient(listOf(glowA, Color.Transparent), c1, r), r, c1)
                drawCircle(Brush.radialGradient(listOf(glowB, Color.Transparent), c2, r * 0.9f), r * 0.9f, c2)
            },
        content = content,
    )
}

/**
 * A novel preview: the cover inside a glass frame, with a frosted strip (the cover itself,
 * blurred) at the bottom of the cover carrying the rating and status.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelCard(
    novel: NovelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val entry = LibraryStore.entries[novel.url]
    Column(
        modifier
            .scale(if (hovered) 1.02f else 1f)
            .glass(colors, RoundedCornerShape(18.dp), strong = hovered)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(7.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(13.dp))) {
            val fullHeight = maxHeight
            RemoteImage(novel.cover, Modifier.fillMaxSize())
            val strip = novel.rating != null || novel.status != null || novel.latestChapter != null
            if (strip) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(0.dp))) {
                    // Same cover, blurred and aligned to the card's bottom: a real frosted-glass strip.
                    RemoteImage(
                        novel.cover,
                        Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true)
                            .requiredHeight(fullHeight).blur(14.dp),
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .background(colors.spec.glass.copy(alpha = 0.45f))
                            .border(0.5.dp, colors.glassEdge, RoundedCornerShape(0.dp))
                    )
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            novel.latestChapter ?: novel.status.orEmpty(),
                            color = colors.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        novel.rating?.let { RatingTag(it) }
                    }
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (badge != null) Pill(badge, colors.accent, colors.onAccent)
                if (entry?.newChapters ?: 0 > 0) Pill("+${entry!!.newChapters}", colors.accent, colors.onAccent)
            }
            if (entry?.favorite == true) {
                Icon(
                    Icons.Filled.Favorite, null, tint = colors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            novel.title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val sub = subtitle ?: novel.genres.take(2).joinToString(" • ").ifEmpty { null }
        if (sub != null) {
            Text(
                sub, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (footer != null) Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { footer() }
    }
}

/** How far into the novel the reader is: "10/556", "2%" and a bar that fills up. Nothing until a chapter was read. */
@Composable
fun ReadingProgress(entry: LibraryEntry?, modifier: Modifier = Modifier, big: Boolean = false) {
    val colors = LocalAppColors.current
    val index = entry?.lastChapterIndex ?: 0
    val total = maxOf(entry?.knownChapters ?: 0, index)
    if (index <= 0 || total <= 0) return
    val fraction = index.toFloat() / total
    val percent = if (index >= total) 100 else (fraction * 100).roundToInt().coerceIn(1, 99)
    val size = if (big) 14.sp else 12.sp
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$index/$total", color = colors.text, fontSize = size, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("$percent%", color = colors.accent, fontSize = size, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { fraction }, color = colors.accent, trackColor = colors.glassFillStrong,
            gapSize = 0.dp, drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(if (big) 8.dp else 5.dp).clip(CircleShape),
        )
    }
}

@Composable
fun RatingTag(rating: String) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(2.dp))
        Text(Regex("\\d+(\\.\\d+)?").find(rating)?.value ?: rating, color = colors.text, fontSize = 11.sp)
    }
}

@Composable
fun Pill(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = content, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@Composable
fun GlassChip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = LocalAppColors.current
    val shape = CircleShape
    val base = if (selected) Modifier.clip(shape).background(colors.accent) else Modifier.glass(colors, shape)
    Text(
        text,
        color = if (selected) colors.onAccent else colors.text,
        fontSize = 13.sp,
        modifier = base
            .then(if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val colors = LocalAppColors.current
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 22.dp).clip(CircleShape).background(colors.accent))
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalAppColors.current.accent)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassPanel(padding = 20.dp) {
            Text("تعذر التحميل", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(message, color = colors.muted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            AccentButton("إعادة المحاولة", Icons.Filled.Refresh, onRetry)
        }
    }
}

@Composable
fun AccentButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    Button(
        onClick, modifier.pointerHoverIcon(PointerIcon.Hand), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GlassButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier.glass(colors, RoundedCornerShape(12.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint ?: colors.text)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
