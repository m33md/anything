/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

/** iOS's system colours in dark mode, which the phone's settings tiles are painted with. */
object SystemColors {
    val Red = Color(0xFFFF453A)
    val Orange = Color(0xFFFF9F0A)
    val Yellow = Color(0xFFFFD60A)
    val Green = Color(0xFF30D158)
    val Teal = Color(0xFF40CBE0)
    val Cyan = Color(0xFF64D2FF)
    val Blue = Color(0xFF0A84FF)
    val Indigo = Color(0xFF5E5CE6)
    val Purple = Color(0xFFBF5AF2)
    val Pink = Color(0xFFFF375F)
    val Gray = Color(0xFF8E8E93)
}

/** The phone's inset-grouped card: #1C1C1E with its 26 dp continuous corners. */
private val GroupedCard = ConvxColors.Card
private val GroupedShape = ContinuousRoundedRectangle(26.dp)

/** Rows with a tile line their separator up with the text, past the tile. */
val TileInset = 58.dp
val PlainInset = 16.dp

/**
 * One inset-grouped section: a small grey heading, the rounded card, and an optional footnote
 * under it, as iOS Settings lays them out.
 */
@Composable
fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = ConvxType.SubheadStrong,
                color = ConvxColors.SecondaryLabel,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(GroupedShape).background(GroupedCard), content = content)
        if (footer != null) {
            Text(
                footer,
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
    }
}

/** The hairline between two rows of a card, starting under the text. */
@Composable
fun RowDivider(start: Dp = TileInset) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(ConvxColors.Separator))
}

/** The coloured rounded square with a white glyph that leads a settings row. */
@Composable
fun SettingsTile(icon: String, color: Color, size: Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(ContinuousRoundedRectangle(size * 0.235f)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = Color.White, size = size * 0.6f)
    }
}

/** The row's press highlight: a light wash on hover, a stronger one held down, like iOS's row press. */
@Composable
fun Modifier.rowPress(onClick: (() -> Unit)?, enabled: Boolean = true, selected: Boolean = false): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash = when {
        selected -> Color.White.copy(alpha = 0.12f)
        onClick == null || !enabled -> Color.Transparent
        pressed -> Color.White.copy(alpha = 0.10f)
        hovered -> Color.White.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    return this
        .background(wash)
        .hoverable(interaction, enabled = onClick != null && enabled)
        .then(
            if (onClick != null && enabled) {
                Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            } else {
                Modifier
            },
        )
}

/**
 * The shape every row shares: an optional tile, a title with a two-line description under it,
 * and whatever sits at the end.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: String? = null,
    tint: Color = SystemColors.Gray,
    enabled: Boolean = true,
    selected: Boolean = false,
    titleColor: Color = ConvxColors.Label,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowPress(onClick, enabled, selected)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            icon != null -> {
                SettingsTile(icon, tint)
                Spacer(Modifier.width(14.dp))
            }
            else -> Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ConvxType.Body, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row that opens a page: a value on the right if it has one, then the chevron. */
@Composable
fun LinkRow(
    title: String,
    subtitle: String? = null,
    icon: String? = null,
    tint: Color = SystemColors.Gray,
    value: String? = null,
    selected: Boolean = false,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    SettingsRow(title, subtitle, icon, tint, selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge > 0) {
                Box(Modifier.size(22.dp).clip(ContinuousRoundedRectangle(11.dp)).background(SystemColors.Red), contentAlignment = Alignment.Center) {
                    Text("$badge", style = ConvxType.SubheadStrong, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (value != null) {
                Text(value, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp)
        }
    }
}

/** A row with the phone's glass switch; clicking anywhere on it flips the switch. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    icon: String? = null,
    tint: Color = SystemColors.Gray,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(title, subtitle, icon, tint, enabled = enabled, onClick = { onChange(!checked) }) {
        GlassSwitch(checked, onChange, enabled = enabled)
    }
}

/**
 * A row that picks one of a few values, as iOS's pop-up buttons do: the value and an up-down
 * chevron on the right, and the choices in a menu.
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    icon: String? = null,
    tint: Color = SystemColors.Gray,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val menu = rememberMenuAnchor()
    SettingsRow(title, subtitle, icon, tint, enabled = enabled, onClick = { menu.expanded = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty(),
                    style = ConvxType.Body,
                    color = ConvxColors.SecondaryLabel,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                ConvxIcon("chevron_up_down", tint = ConvxColors.SecondaryLabel, size = 16.dp)
            }
            AppMenu(menu) {
                options.forEach { (value, label) ->
                    MenuItem(label, if (value == selected) "check" else null) {
                        onSelect(value)
                        menu.expanded = false
                    }
                }
            }
        }
    }
}

/** A plain button row, centred or leading, in the accent or a destructive red. */
@Composable
fun ButtonRow(title: String, color: Color = SystemColors.Blue, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowPress(onClick, enabled)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = ConvxType.Body, color = color)
    }
}

/** A grey note with an info glyph, for what a page can't do and why. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(18.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ConvxIcon("info", tint = ConvxColors.SecondaryLabel, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
    }
}
