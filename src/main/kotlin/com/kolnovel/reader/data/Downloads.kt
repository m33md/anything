package com.kolnovel.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Saves chapters to disk one after another, gently, so they can be read offline. */
class Downloads(private val source: KolSource) {
    data class Progress(val title: String, val done: Int, val total: Int, val failed: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set

    fun isRunning(novelUrl: String) = jobs[novelUrl]?.isActive == true

    fun start(novelUrl: String, title: String, chapters: List<ChapterRef>) {
        if (isRunning(novelUrl)) return
        val todo = chapters.filterNot { ChapterStore.has(it.url) }
        if (todo.isEmpty()) {
            progress = progress + (novelUrl to Progress(title, chapters.size, chapters.size, 0))
            return
        }
        jobs[novelUrl] = scope.launch {
            var done = 0
            var failed = 0
            update(novelUrl, Progress(title, 0, todo.size, 0))
            for (ref in todo) {
                if (!isActive) break
                val ok = runCatching { ChapterStore.save(source.chapter(ref.url)) }.getOrDefault(false)
                if (ok) done++ else failed++
                update(novelUrl, Progress(title, done, todo.size, failed))
                delay(350)
            }
        }
    }

    fun cancel(novelUrl: String) {
        jobs.remove(novelUrl)?.cancel()
        progress = progress - novelUrl
    }

    @Synchronized
    private fun update(url: String, p: Progress) {
        progress = progress + (url to p)
    }
}
