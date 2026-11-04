/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.cos
import org.jetbrains.skia.Image as SkiaImage

// The phone's DIY: stickers, images and emoji placed over the player. The model is the phone's,
// with its JSON names, so a layout reads the same on both.

/** The phone's ceiling: ten stickers, and 8 MB of their images. */
const val DiyMaxStickers = 10
const val DiyMaxAssetBytes = 8L * 1024 * 1024

@Serializable
enum class DiyStickerKind { IMAGE, EMOJI }

/** Which way up the player is; each keeps its own placement. A PC window wider than tall is landscape. */
enum class DiyOrientation { PORTRAIT, LANDSCAPE }

/**
 * Where a sticker sits, in fractions of the player rather than pixels: [x] and [y] are its centre,
 * [scale] the share of the player's shorter edge its longest side spans, [rotation] degrees clockwise.
 */
@Serializable
data class DiyTransform(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 0.25f,
    val rotation: Float = 0f,
) {
    fun clamped() = copy(x = x.coerceIn(-0.5f, 1.5f), y = y.coerceIn(-0.5f, 1.5f), scale = scale.coerceIn(MinScale, MaxScale))

    companion object {
        const val MinScale = 0.03f
        const val MaxScale = 2.0f
    }
}

/**
 * One placed item. [source] is the image's file name in the sticker folder, or the emoji itself.
 * [z] below zero draws behind the player's cover, the rest in front of it.
 */
@Serializable
data class DiySticker(
    val id: String,
    val kind: DiyStickerKind,
    val source: String,
    val z: Int = 1,
    val opacity: Float = 1f,
    @SerialName("flipH") val flipHorizontal: Boolean = false,
    @SerialName("corner") val cornerRadius: Float = 0f,
    val shadow: Boolean = false,
    val portrait: DiyTransform = DiyTransform(),
    val landscape: DiyTransform = DiyTransform(),
) {
    fun transformFor(orientation: DiyOrientation) = if (orientation == DiyOrientation.PORTRAIT) portrait else landscape

    fun withTransform(orientation: DiyOrientation, transform: DiyTransform) =
        if (orientation == DiyOrientation.PORTRAIT) copy(portrait = transform) else copy(landscape = transform)

    companion object {
        const val MaxZ = 20
    }
}

@Serializable
data class DiyLayout(val version: Int = 1, val stickers: List<DiySticker> = emptyList()) {
    val isEmpty: Boolean get() = stickers.isEmpty()
}

/** The sticker images, in the app's data like Player icons' pictures. */
object DiyStore {
    val dir: File by lazy { File(AppDirs.data, "diy_stickers").apply { mkdirs() } }
    val Extensions = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")

    /** A sticker is never drawn bigger than a window, so pictures beyond this are kept smaller. */
    private const val MaxSide = 2048
    private val painters = ConcurrentHashMap<String, Painter>()

    fun file(source: String): File? =
        source.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ".." !in it && it.length <= 128 }?.let { File(dir, it) }

    fun assetBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Takes a picture in as a sticker and returns its file name. Throws, with words to show, if it can't. */
    fun import(source: File): String {
        val bytes = source.readBytes()
        require(bytes.size <= 20 * 1024 * 1024) { "That image is over 20 MB." }
        val id = UUID.randomUUID().toString()
        val (extension, kept) = if (source.extension.equals("svg", ignoreCase = true)) {
            requireNotNull(runCatching { SVGDOM(Data.makeFromBytes(bytes)).root }.getOrNull()) { "This SVG can't be read." }
            "svg" to bytes
        } else {
            val image = requireNotNull(runCatching { SkiaImage.makeFromEncoded(bytes) }.getOrNull()) {
                "This isn't an image Convx can read. Use PNG, JPEG, WebP, GIF, BMP or SVG."
            }
            val side = maxOf(image.width, image.height)
            if (side <= MaxSide) {
                source.extension.lowercase().ifEmpty { "png" } to bytes
            } else {
                val scale = MaxSide.toFloat() / side
                val width = (image.width * scale).toInt().coerceAtLeast(1)
                val height = (image.height * scale).toInt().coerceAtLeast(1)
                val surface = Surface.makeRasterN32Premul(width, height)
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                "png" to requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes) { "Couldn't shrink this image." }
            }
        }
        require(assetBytes() + kept.size <= DiyMaxAssetBytes) { "Sticker storage is full. Remove a sticker to add another." }
        val name = "$id.$extension"
        File(dir, name).writeBytes(kept)
        return name
    }

    /** The smaller side of an image sticker in pixels, for the low-resolution hint; 0 for SVG or unknown. */
    fun pixelSide(source: String): Int {
        val file = file(source)?.takeIf { it.isFile && !it.extension.equals("svg", ignoreCase = true) } ?: return 0
        return runCatching { SkiaImage.makeFromEncoded(file.readBytes()).let { min(it.width, it.height) } }.getOrDefault(0)
    }

    /** Deletes pictures no layout uses any more. */
    fun prune(layout: DiyLayout) {
        val live = layout.stickers.filter { it.kind == DiyStickerKind.IMAGE }.mapTo(HashSet()) { it.source }
        dir.listFiles()?.forEach { if (it.name !in live && !it.name.endsWith(".tmp")) it.delete() }
    }

    @Suppress("DEPRECATION")
    suspend fun painter(source: String): Painter? {
        painters[source]?.let { return it }
        val file = file(source)?.takeIf { it.isFile } ?: return null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (file.extension.equals("svg", ignoreCase = true)) file.inputStream().use { loadSvgPainter(it, Density(1f)) }
                else BitmapPainter(SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap())
            }.getOrNull()
        } ?: return null
        painters[source] = loaded
        return loaded
    }
}

/** A window wider than it is tall holds the landscape placement. */
fun orientationOf(width: Dp, height: Dp) = if (width >= height) DiyOrientation.LANDSCAPE else DiyOrientation.PORTRAIT

// The slices the player draws, kept as values so the layer isn't refiltered on every frame.
val AllStickers: (Int) -> Boolean = { true }
val BehindCover: (Int) -> Boolean = { it < 0 }
val InFrontOfCover: (Int) -> Boolean = { it >= 0 }

/** The phone's sticker entrance: an underdamped spring that settles exactly at the end of its window. */
private object PopEasing : Easing {
    private const val Zeta = 0.4f
    private const val OmegaT = 9f
    private val root = sqrt(1f - Zeta * Zeta)
    private val end = raw(1f)
    private fun raw(t: Float): Float {
        val wd = OmegaT * root * t
        return 1f - exp(-Zeta * OmegaT * t) * (cos(wd) + Zeta / root * sin(wd))
    }
    override fun transform(fraction: Float): Float = if (fraction >= 1f) 1f else raw(fraction) + (1f - end) * fraction
}

/**
 * The stickers over the player. It never takes a click, so a sticker over a button can't swallow
 * it. [zFilter] picks the slice: the player draws the layer twice, behind and in front of its cover.
 * [entrance] (0 to 1) pops them in one after another, top-left to bottom-right, as the player opens.
 */
@Composable
fun DiyStickerLayer(
    layout: DiyLayout,
    modifier: Modifier = Modifier,
    zFilter: (Int) -> Boolean = AllStickers,
    entrance: (() -> Float)? = null,
) {
    if (layout.isEmpty) return
    val visible = remember(layout, zFilter) { layout.stickers.filter { zFilter(it.z) }.sortedBy { it.z } }
    if (visible.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val orientation = orientationOf(width, height)
        val ranks = remember(visible, orientation) {
            val order = visible.sortedBy { val t = it.transformFor(orientation); t.y + 0.35f * t.x }
            val last = (order.size - 1).coerceAtLeast(1)
            order.withIndex().associate { (i, sticker) -> sticker.id to i.toFloat() / last }
        }
        visible.forEach { sticker ->
            val rank = ranks[sticker.id] ?: 0f
            DiyStickerContent(
                sticker,
                orientation,
                width,
                height,
                entrance = entrance?.let { progress -> { ((progress() - (0.2f + 0.2f * rank)) / 0.6f).coerceIn(0f, 1f) } },
            )
        }
    }
}

/** One sticker, placed and turned in a graphics layer, so moving it never redraws its picture. */
@Composable
fun DiyStickerContent(
    sticker: DiySticker,
    orientation: DiyOrientation,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    entrance: (() -> Float)? = null,
) {
    val density = LocalDensity.current
    val transform = sticker.transformFor(orientation)
    val size = Dp(min(width.value, height.value)) * transform.scale
    val offsetX = with(density) { (width * transform.x - size / 2).toPx() }
    val offsetY = with(density) { (height * transform.y - size / 2).toPx() }
    // The caller's modifier (the editor's selection border) goes on after the move, so it sits on the sticker.
    val placed = Modifier
        .size(size)
        .graphicsLayer {
            translationX = offsetX
            translationY = offsetY
            rotationZ = transform.rotation
            val t = entrance?.invoke() ?: 1f
            // Pops from a third of its size, overshoots and settles, fading in over its first third.
            val pop = if (t >= 1f) 1f else 0.3f + 0.7f * PopEasing.transform(t)
            alpha = sticker.opacity * (t * 3f).coerceAtMost(1f)
            scaleX = if (sticker.flipHorizontal) -pop else pop
            scaleY = pop
        }
        .then(modifier)
    when (sticker.kind) {
        DiyStickerKind.EMOJI -> Box(placed) {
            Text(
                sticker.source,
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = with(density) { (size.toPx() * 0.82f).toSp() }),
                modifier = Modifier.fillMaxSize(),
            )
        }
        DiyStickerKind.IMAGE -> {
            val painter by produceState<Painter?>(null, sticker.source) { value = DiyStore.painter(sticker.source) }
            val image = painter ?: return
            val shape = RoundedCornerShape(percent = (sticker.cornerRadius * 100).toInt())
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = placed.then(if (sticker.shadow) Modifier.shadow(8.dp, shape) else Modifier).clip(shape),
            )
        }
    }
}
