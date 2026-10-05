package com.kolnovel.reader.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** The few icons the app needs that are not in the small built-in icon set. */
object KolIcons {
    val Download: ImageVector = materialIcon(name = "Kol.Download") {
        materialPath {
            moveTo(19f, 9f)
            horizontalLineToRelative(-4f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            lineToRelative(7f, 7f)
            lineToRelative(7f, -7f)
            close()
            moveTo(5f, 18f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineTo(5f)
            close()
        }
    }

    val Pause: ImageVector = materialIcon(name = "Kol.Pause") {
        materialPath {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineToRelative(-4f)
            close()
        }
    }

    /** A phone with a check: "saved on this device". */
    val Offline: ImageVector = materialIcon(name = "Kol.Offline") {
        materialPath {
            moveTo(17f, 1f)
            horizontalLineTo(7f)
            curveTo(5.9f, 1f, 5f, 1.9f, 5f, 3f)
            verticalLineToRelative(18f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(10f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(3f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(17f, 19f)
            horizontalLineTo(7f)
            verticalLineTo(5f)
            horizontalLineToRelative(10f)
            verticalLineToRelative(14f)
            close()
            moveTo(10.5f, 15.5f)
            lineToRelative(-3f, -3f)
            lineToRelative(1.4f, -1.4f)
            lineToRelative(1.6f, 1.6f)
            lineToRelative(4.6f, -4.6f)
            lineToRelative(1.4f, 1.4f)
            close()
        }
    }
}
