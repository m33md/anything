package com.kolnovel.reader.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Material "file_download" (the core icon set has no download arrow). */
val DownloadIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Download") {
        materialPath {
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineTo(9f); verticalLineToRelative(6f)
            horizontalLineTo(5f); lineToRelative(7f, 7f); lineToRelative(7f, -7f); close()
            moveTo(5f, 18f); verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(5f); close()
        }
    }
}
