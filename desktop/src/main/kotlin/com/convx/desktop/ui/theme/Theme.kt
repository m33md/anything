/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone app's Apple-dark palette (AppleTokens / iOS system colours). */
object ConvxColors {
    val AccentRed = Color(0xFFFA2D48)

    val Bg = Color(0xFF121212)
    val BgElevated = Color(0xFF1A1A1A)
    val Card = Color(0xFF1C1C1E)
    val CardSecondary = Color(0xFF2C2C2E)
    val CardTertiary = Color(0xFF3A3A3C)
    val Panel = Color(0xFF0A0A0A)

    val Label = Color.White
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val SystemGray = Color(0xFF8E8E93)
    val Separator = Color(0x29FFFFFF)
    val Fill = Color(0x1FFFFFFF)
    val FillStrong = Color(0x33FFFFFF)
}

/** Corner radii — the phone's four corners plus the side panel's. */
object ConvxShapes {
    val Artwork = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(14.dp)
    val CardLarge = RoundedCornerShape(18.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Capsule = RoundedCornerShape(percent = 50)
}

object ConvxFonts {
    private fun font(path: String, weight: FontWeight) = Font(
        identity = path,
        data = ConvxFonts::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() },
        weight = weight,
    )

    /** SF Pro Text — the phone's title and body face. */
    val SfPro: FontFamily by lazy {
        FontFamily(
            font("fonts/sf_pro_text_regular.otf", FontWeight.Normal),
            font("fonts/sf_pro_text_medium.otf", FontWeight.Medium),
            font("fonts/sf_pro_text_semibold.otf", FontWeight.SemiBold),
            font("fonts/sf_pro_text_bold.otf", FontWeight.Bold),
        )
    }

    /** Chillax — the brand display face (wordmark). */
    val Chillax: FontFamily by lazy {
        FontFamily(
            font("fonts/chillax_light.otf", FontWeight.Light),
            font("fonts/chillax_regular.otf", FontWeight.Normal),
            font("fonts/chillax_medium.otf", FontWeight.Medium),
            font("fonts/chillax_semibold.otf", FontWeight.SemiBold),
            font("fonts/chillax_bold.otf", FontWeight.Bold),
        )
    }
}

/** Design-pinned text styles from AppleTokens; sizes carry their own line heights. */
object ConvxType {
    private fun sf(size: TextUnit, line: TextUnit, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
        fontFamily = ConvxFonts.SfPro,
        fontSize = size,
        lineHeight = line,
        fontWeight = weight,
        letterSpacing = tracking,
    )

    val LargeTitle by lazy { sf(34.sp, 41.sp, FontWeight.Bold, (-0.6).sp) }
    val Hero by lazy { sf(56.sp, 60.sp, FontWeight.Bold, (-1.2).sp) }
    val Title1 by lazy { sf(28.sp, 34.sp, FontWeight.Bold, (-0.4).sp) }
    val Title2 by lazy { sf(22.sp, 28.sp, FontWeight.Bold, (-0.3).sp) }
    val Title3 by lazy { sf(20.sp, 25.sp, FontWeight.SemiBold, (-0.2).sp) }
    val Headline by lazy { sf(17.sp, 22.sp, FontWeight.SemiBold, (-0.43).sp) }
    val Body by lazy { sf(15.sp, 20.sp, FontWeight.Normal, (-0.2).sp) }
    val BodyStrong by lazy { sf(15.sp, 20.sp, FontWeight.SemiBold, (-0.2).sp) }
    val Subhead by lazy { sf(13.sp, 18.sp, FontWeight.Normal, (-0.08).sp) }
    val SubheadStrong by lazy { sf(13.sp, 18.sp, FontWeight.SemiBold, (-0.08).sp) }
    val Caption by lazy { sf(12.sp, 16.sp, FontWeight.Normal) }
    val CaptionStrong by lazy { sf(12.sp, 16.sp, FontWeight.SemiBold) }
    val Footnote by lazy { sf(11.sp, 13.sp, FontWeight.Medium, 0.06.sp) }
    val Lyrics by lazy { sf(34.sp, 42.sp, FontWeight.Bold, (-0.5).sp) }
    val LyricsCompact by lazy { sf(22.sp, 29.sp, FontWeight.Bold, (-0.3).sp) }
}

/** The user's accent; only interactive highlights take it, like the phone. */
val LocalAccent = compositionLocalOf { ConvxColors.AccentRed }

/** Colours a hero page derived from its artwork; null outside one. */
val LocalHeroPalette = staticCompositionLocalOf<HeroColors?> { null }

@Composable
fun ConvxTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        secondary = ConvxColors.SecondaryLabel,
        background = ConvxColors.Bg,
        onBackground = Color.White,
        surface = ConvxColors.Bg,
        onSurface = Color.White,
        onSurfaceVariant = ConvxColors.SecondaryLabel,
        surfaceVariant = ConvxColors.CardSecondary,
        surfaceContainerLowest = ConvxColors.Bg,
        surfaceContainerLow = ConvxColors.BgElevated,
        surfaceContainer = Color(0xFF252527),
        surfaceContainerHigh = ConvxColors.CardSecondary,
        surfaceContainerHighest = ConvxColors.CardTertiary,
        outline = ConvxColors.Separator,
        outlineVariant = ConvxColors.Separator,
        error = Color(0xFFFF453A),
    )
    val base = TextStyle(fontFamily = ConvxFonts.SfPro)
    val typography = Typography().let { t ->
        Typography(
            displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
            headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
            titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(18.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalContentColor provides Color.White,
            LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f)),
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 250,
                unhoverColor = Color.White.copy(alpha = 0.16f),
                hoverColor = Color.White.copy(alpha = 0.38f),
            ),
            content = content,
        )
    }
}
