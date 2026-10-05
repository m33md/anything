package com.kolnovel.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import com.kolnovel.reader.R
import androidx.compose.ui.unit.dp
import com.kolnovel.reader.data.Settings

/** A base look the user can pick in الإعدادات. Every color in the app comes from one of these. */
@Immutable
data class ThemeSpec(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Color,
    val backgroundAlt: Color,
    val text: Color,
    val muted: Color,
    /** Color the glass is tinted with. */
    val glass: Color,
)

val Themes = listOf(
    ThemeSpec("black", "أسود", true, Color(0xFF000000), Color(0xFF0B0B0D), Color(0xFFF2F2F2), Color(0xFF9A9AA0), Color(0xFF1C1C20)),
    ThemeSpec("charcoal", "رمادي داكن", true, Color(0xFF16171A), Color(0xFF1F2024), Color(0xFFEDEDEF), Color(0xFF9EA0A6), Color(0xFF2C2D33)),
    ThemeSpec("grey", "رمادي", true, Color(0xFF2E3035), Color(0xFF383A40), Color(0xFFF1F1F3), Color(0xFFB4B6BC), Color(0xFF4A4C53)),
    ThemeSpec("silver", "رمادي فاتح", false, Color(0xFFD9DADE), Color(0xFFE6E7EA), Color(0xFF17181B), Color(0xFF55575D), Color(0xFFFFFFFF)),
    ThemeSpec("white", "أبيض", false, Color(0xFFF6F6F8), Color(0xFFFFFFFF), Color(0xFF141416), Color(0xFF5E6067), Color(0xFFFFFFFF)),
    ThemeSpec("sepia", "ورقي", false, Color(0xFFF1E7D0), Color(0xFFF7EFDC), Color(0xFF3A2E1E), Color(0xFF7A6A52), Color(0xFFFFF8E8)),
    ThemeSpec("navy", "كحلي", true, Color(0xFF0B1220), Color(0xFF111A2C), Color(0xFFE8EDF7), Color(0xFF8E9BB3), Color(0xFF1B2740)),
    ThemeSpec("wine", "ليلي ماروني", true, Color(0xFF12060A), Color(0xFF1C0A10), Color(0xFFF4E9EC), Color(0xFFB0959C), Color(0xFF2E121A)),
)

/** The site's own maroon first, then the other accents. */
val Accents = listOf(
    "maroon" to ("ماروني (الموقع)" to Color(0xFF8B1315)),
    "red" to ("أحمر" to Color(0xFFE53935)),
    "rose" to ("وردي" to Color(0xFFE91E63)),
    "purple" to ("بنفسجي" to Color(0xFF8E44EC)),
    "blue" to ("أزرق" to Color(0xFF2F7CF6)),
    "teal" to ("فيروزي" to Color(0xFF14B8A6)),
    "green" to ("أخضر" to Color(0xFF22A45D)),
    "orange" to ("برتقالي" to Color(0xFFF57C1F)),
    "gold" to ("ذهبي" to Color(0xFFD4A82A)),
    "mono" to ("أبيض/أسود" to Color(0xFF9E9E9E)),
)

@Immutable
data class AppColors(
    val spec: ThemeSpec,
    val accent: Color,
    val onAccent: Color,
    val glassOpacity: Float,
) {
    val dark get() = spec.dark
    val background get() = spec.background
    val text get() = spec.text
    val muted get() = spec.muted
    val glassFill get() = spec.glass.copy(alpha = glassOpacity)
    val glassFillStrong get() = spec.glass.copy(alpha = (glassOpacity + 0.25f).coerceAtMost(0.95f))
    val glassEdge get() = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f)
    val glassShadowEdge get() = if (dark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.10f)
}

fun parseHex(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    return clean.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

fun themeById(id: String) = Themes.firstOrNull { it.id == id } ?: Themes.first()

fun colorsFor(settings: Settings, themeOverride: String? = null): AppColors {
    val spec = themeById(themeOverride?.ifEmpty { null } ?: settings.theme)
    var accent = parseHex(settings.customAccent)
        ?: Accents.firstOrNull { it.first == settings.accent }?.second?.second
        ?: Accents.first().second.second
    if (settings.accent == "mono" && settings.customAccent.isBlank()) {
        accent = if (spec.dark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    }
    // The site's maroon is too dark to read on black, so lift it a little on dark themes.
    if (spec.dark && accent.luminance() < 0.06f) accent = lerpColor(accent, Color.White, 0.22f)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    return AppColors(spec, accent, onAccent, settings.glassOpacity)
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

val LocalAppColors = staticCompositionLocalOf { colorsFor(Settings()) }

val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
    Font(R.font.cairo_black, FontWeight.Black),
)

fun readerFontFamily(id: String): FontFamily = when (id) {
    "serif" -> FontFamily.Serif
    "system" -> FontFamily.SansSerif
    else -> Cairo
}

private fun TextStyle.cairo() = copy(fontFamily = Cairo)

@Composable
fun KolTheme(colors: AppColors, content: @Composable () -> Unit) {
    val scheme = if (colors.dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent,
        secondary = colors.accent, onSecondary = colors.onAccent,
        background = colors.background, onBackground = colors.text,
        surface = colors.spec.backgroundAlt, onSurface = colors.text,
        surfaceVariant = colors.spec.glass, onSurfaceVariant = colors.muted,
        surfaceContainer = colors.spec.backgroundAlt, surfaceContainerHigh = colors.spec.glass,
        outline = colors.muted.copy(alpha = 0.5f),
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent,
        secondary = colors.accent, onSecondary = colors.onAccent,
        background = colors.background, onBackground = colors.text,
        surface = colors.spec.backgroundAlt, onSurface = colors.text,
        surfaceVariant = colors.spec.backgroundAlt, onSurfaceVariant = colors.muted,
        surfaceContainer = colors.spec.backgroundAlt, surfaceContainerHigh = colors.spec.glass,
        outline = colors.muted.copy(alpha = 0.5f),
    )
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.cairo(), displayMedium = base.displayMedium.cairo(),
        displaySmall = base.displaySmall.cairo(), headlineLarge = base.headlineLarge.cairo(),
        headlineMedium = base.headlineMedium.cairo(), headlineSmall = base.headlineSmall.cairo(),
        titleLarge = base.titleLarge.cairo().copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.cairo().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.cairo().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.cairo(), bodyMedium = base.bodyMedium.cairo(), bodySmall = base.bodySmall.cairo(),
        labelLarge = base.labelLarge.cairo(), labelMedium = base.labelMedium.cairo(), labelSmall = base.labelSmall.cairo(),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
