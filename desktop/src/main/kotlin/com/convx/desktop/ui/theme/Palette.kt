/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colours a hero screen paints itself from: like the phone's HeroPalette, the
 * artwork's own most vivid colours rather than a "safe" seed.
 */
@Immutable
data class HeroColors(
    /** The page's flat tint. */
    val background: Color,
    /** A second, distinct swatch for highlights and the mesh wash. */
    val accent: Color,
    val primaryText: Color = Contrast.onColor(background),
    val secondaryText: Color = Contrast.onColorSecondary(background),
    val heading: Color = Contrast.onColorHeading(background),
)

object Palette {
    /**
     * Samples the bitmap on a coarse grid, buckets by hue, and picks the heaviest
     * chromatic bucket (dominant) and the most saturated bright one (accent).
     * Achromatic covers get greys, never an invented colour.
     */
    fun extract(bitmap: ImageBitmap): HeroColors {
        val step = max(1, min(bitmap.width, bitmap.height) / 40)
        val pixels = bitmap.toPixelMap()
        val bins = Array(24) { FloatArray(5) } // weight, r, g, b, vivid score
        var greyWeight = 0f
        var greyLum = 0f
        var samples = 0
        val hsv = FloatArray(3)
        var y = step / 2
        while (y < bitmap.height) {
            var x = step / 2
            while (x < bitmap.width) {
                val c = pixels[x, y]
                samples++
                rgbToHsv(c.red, c.green, c.blue, hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s < 0.18f || v < 0.12f) {
                    greyWeight += 1f
                    greyLum += v
                } else {
                    val bin = ((hsv[0] / 360f) * bins.size).toInt().coerceIn(0, bins.lastIndex)
                    val w = s * (0.35f + v)
                    bins[bin][0] += w
                    bins[bin][1] += c.red * w
                    bins[bin][2] += c.green * w
                    bins[bin][3] += c.blue * w
                    bins[bin][4] = max(bins[bin][4], s * v)
                }
                x += step
            }
            y += step
        }
        val chromatic = bins.sumOf { it[0].toDouble() }.toFloat()
        // Mostly grey artwork: stay grey, tinted by its own brightness.
        if (chromatic < samples * 0.06f) {
            val lum = if (greyWeight > 0) greyLum / greyWeight else 0.3f
            val bg = Color.hsv(0f, 0f, (0.10f + lum * 0.18f).coerceIn(0.08f, 0.26f))
            return HeroColors(background = bg, accent = Color.hsv(0f, 0f, 0.62f))
        }
        // Merge each bin with its neighbours so a hue split over a bin edge still wins.
        val merged = bins.indices.map { i ->
            val a = bins[(i + bins.size - 1) % bins.size]
            val b = bins[i]
            val c = bins[(i + 1) % bins.size]
            i to (a[0] * 0.5f + b[0] + c[0] * 0.5f)
        }
        val dominantBin = merged.maxBy { it.second }.first
        val dominant = bins[dominantBin].average()
        val accentBin = bins.indices
            .filter { abs(it - dominantBin).let { d -> min(d, bins.size - d) } >= 3 && bins[it][0] > chromatic * 0.04f }
            .maxByOrNull { bins[it][4] * bins[it][0] }
        val accent = accentBin?.let { bins[it].average() } ?: dominant
        return HeroColors(
            background = pageTint(dominant),
            accent = vivid(accent),
        )
    }

    private fun FloatArray.average(): Color =
        if (this[0] <= 0f) Color.Gray else Color(this[1] / this[0], this[2] / this[0], this[3] / this[0])

    /** A page background from a swatch: same hue, deep enough for white text, still clearly coloured. */
    fun pageTint(color: Color): Color {
        val hsl = color.toHsl()
        hsl[1] = hsl[1].coerceIn(0.18f, 0.62f)
        hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
        return hslToColor(hsl)
    }

    /** A brighter version for glows and highlight blobs. */
    fun vivid(color: Color): Color {
        val hsl = color.toHsl()
        hsl[1] = hsl[1].coerceIn(0.35f, 0.9f)
        hsl[2] = hsl[2].coerceIn(0.42f, 0.62f)
        return hslToColor(hsl)
    }
}

/** Ports of AppleTokens' adaptive-contrast helpers. */
object Contrast {
    private const val LightContentMaxLuminance = 0.179f
    private const val MinTextContrast = 4.5f

    fun prefersLightContent(bg: Color) = bg.luminance() <= LightContentMaxLuminance

    fun ratio(a: Color, b: Color): Float {
        val l1 = max(a.luminance(), b.luminance())
        val l2 = min(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    fun onColor(bg: Color) = bg.shifted(saturation = 0.16f, lightness = 0.96f to 0.10f)
    fun onColorSecondary(bg: Color) = bg.shifted(saturation = 0.42f, lightness = 0.76f to 0.34f)
    fun onColorHeading(bg: Color) = bg.shifted(saturation = 0.55f, lightness = 0.88f to 0.18f)

    private fun Color.shifted(saturation: Float, lightness: Pair<Float, Float>): Color {
        val hsl = toHsl()
        val onDark = prefersLightContent(this)
        val target = if (onDark) lightness.first else lightness.second
        if (hsl[1] < 0.08f) {
            hsl[1] = 0f
            hsl[2] = if (target > 0.9f) 1f else if (target < 0.12f) 0.04f else target
        } else {
            hsl[1] = min(hsl[1], saturation)
            hsl[2] = target
        }
        var result = hslToColor(hsl)
        var steps = 0
        while (ratio(result, this) < MinTextContrast && steps++ < 12) {
            hsl[2] = (hsl[2] + if (onDark) 0.04f else -0.04f).coerceIn(0f, 1f)
            result = hslToColor(hsl)
        }
        return result
    }
}

fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val d = maxC - minC
    val h = when {
        d == 0f -> 0f
        maxC == r -> 60f * (((g - b) / d) % 6f)
        maxC == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    out[0] = if (h < 0) h + 360f else h
    out[1] = if (maxC == 0f) 0f else d / maxC
    out[2] = maxC
}

fun Color.toHsl(): FloatArray {
    val maxC = max(red, max(green, blue))
    val minC = min(red, min(green, blue))
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (maxC) {
        red -> 60f * (((green - blue) / d) % 6f)
        green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }
    return floatArrayOf(if (h < 0) h + 360f else h, s.coerceIn(0f, 1f), l)
}

fun hslToColor(hsl: FloatArray): Color = Color.hsl(hsl[0].coerceIn(0f, 359.99f), hsl[1].coerceIn(0f, 1f), hsl[2].coerceIn(0f, 1f))

/** Darkens or lightens toward [target] lightness, keeping hue. */
fun Color.withLightness(target: Float): Color {
    val hsl = toHsl()
    hsl[2] = target
    return hslToColor(hsl)
}

/** The full player's four mesh colours, the phone's MeshGradientBackground palette. */
object MeshColors {
    val Fallback = listOf(Color(0xFF141416), Color(0xFF1E1E22), Color(0xFF16161A), Color(0xFF101012))

    fun extract(bitmap: ImageBitmap): List<Color> {
        val pixels = bitmap.toPixelMap()
        val step = max(1, min(bitmap.width, bitmap.height) / 48)
        // 4 bits a channel: coarse enough to pool a sleeve's colours, fine enough to keep them apart.
        val buckets = HashMap<Int, FloatArray>()
        fun sample(filtered: Boolean) {
            buckets.clear()
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val c = pixels[x, y]
                    val l = (max(c.red, max(c.green, c.blue)) + min(c.red, min(c.green, c.blue))) / 2f
                    if (!filtered || (l > 0.05f && l < 0.95f)) {
                        val key = ((c.red * 15).toInt() shl 8) or ((c.green * 15).toInt() shl 4) or (c.blue * 15).toInt()
                        val acc = buckets.getOrPut(key) { FloatArray(4) }
                        acc[0] += 1f
                        acc[1] += c.red
                        acc[2] += c.green
                        acc[3] += c.blue
                    }
                    x += step
                }
                y += step
            }
        }
        sample(filtered = true)
        if (buckets.isEmpty()) sample(filtered = false)
        val found = buckets.values
            .sortedByDescending { it[0] }
            .map { Color(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }
        val distinct = mutableListOf<Color>()
        for (color in found) {
            if (distinct.none { it.isCloseTo(color) }) distinct += color
            if (distinct.size == 4) break
        }
        val four = when {
            distinct.isEmpty() -> Fallback
            distinct.size >= 4 -> distinct
            else -> {
                val out = distinct.toMutableList()
                var n = 1
                while (out.size < 4) {
                    val hsl = out[(out.size - distinct.size) % distinct.size].toHsl()
                    hsl[0] = (hsl[0] + 24f * n) % 360f
                    hsl[2] = (hsl[2] + 0.12f * n).coerceIn(0.2f, 0.7f)
                    out += hslToColor(hsl)
                    n++
                }
                out
            }
        }
        return four.map { it.tuned() }
    }

    private fun Color.isCloseTo(other: Color): Boolean {
        val a = toHsl()
        val b = other.toHsl()
        val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
        return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
    }

    /** Saturation up, lightness clamped: any sleeve gives a rich, non-muddy mesh. */
    private fun Color.tuned(): Color {
        val hsl = toHsl()
        hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
        return hslToColor(hsl)
    }

    /** The near-black the blobs sit on, in the art's own hue. */
    fun dimmed(color: Color): Color {
        val hsl = color.toHsl()
        hsl[2] = 0.12f
        return hslToColor(hsl)
    }
}
