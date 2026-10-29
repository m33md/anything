/*
 * Vendored from Accompanist Lyrics (github.com/6xingyv/Accompanist), Apache License 2.0.
 * Lyrics UI 1.0.19 (the phone's) and the Lyrics Core 0.4.5 models it was built on; adapted only to build here.
 */
package com.mocharealm.accompanist.lyrics.core.model.karaoke


data class KaraokeSyllable(
    val content: String,
    val start: Int,
    val end: Int,
    val phonetic: String? = null,
) {
    val duration = end - start

    init {
        require(end >= start)
    }

    fun progress(current: Int): Float {
        return when {
            current < start -> 0f
            current in start..end -> (current - start).toFloat() / duration
            current > end -> 1f
            else -> 0f
        }.coerceIn(0f, 1f)
    }
}
