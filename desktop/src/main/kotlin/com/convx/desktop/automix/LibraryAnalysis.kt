/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.LocalMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's "Analyze device and downloaded songs for AutoMix": measures every song kept on
 * this PC ahead of time, so a mix never waits on one. Starting it again while it runs stops it.
 */
object LibraryAnalysis {
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    fun toggle() {
        if (job?.isActive == true) {
            job?.cancel()
            _progress.value = _progress.value.copy(running = false)
            return
        }
        job = AppScope.launch(Dispatchers.IO) {
            val songs = (LocalMusic.songs() + Downloads.done.value.values.map { it.song })
                .distinctBy { it.id }
                .filter { !TrackAnalyzer.isAnalysed(it.id) }
            _progress.value = Progress(running = true, done = 0, total = songs.size)
            var done = 0
            for (song in songs) {
                if (!isActive) break
                val file = TrackAnalyzer.sourceFile(song.id)
                if (file != null) {
                    runCatching { TrackAnalyzer.analyzeNow(song.id, file, (song.duration ?: 0).toDouble()) }
                        .onFailure { Log.w("AutoMix", "Couldn't measure ${song.id}: ${it.message}") }
                }
                done++
                _progress.value = Progress(running = true, done = done, total = songs.size)
            }
            _progress.value = Progress(running = false, done = done, total = songs.size)
        }
    }
}
