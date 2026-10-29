/*
 * Vendored from Accompanist Lyrics (github.com/6xingyv/Accompanist), Apache License 2.0.
 * Lyrics UI 1.0.19 (the phone's) and the Lyrics Core 0.4.5 models it was built on; adapted only to build here.
 */
package com.mocharealm.accompanist.lyrics.core.model


interface ISyncedLine {
    val start:Int
    val end:Int
    val duration:Int
}