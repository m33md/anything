/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Work that outlives the screen that started it, e.g. "play this album" from a card. */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun formatDuration(totalMs: Long): String {
    val totalSec = (totalMs / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun formatSeconds(seconds: Int?): String = if (seconds == null || seconds <= 0) "" else formatDuration(seconds * 1000L)

/** "1 hr 12 min" / "42 min", the phone's album footer wording. */
fun formatTotal(seconds: Long): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "$hours hr ${minutes % 60} min"
        else -> "$minutes min"
    }
}

fun pluralize(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"
