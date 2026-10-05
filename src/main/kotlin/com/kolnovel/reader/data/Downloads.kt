package com.kolnovel.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/** A novel the user downloaded chapters of, with its chapter list, so it can be browsed and read offline. */
@Serializable
data class SavedNovel(
    val url: String,
    val title: String,
    val cover: String? = null,
    /** Every chapter the novel had when last seen, oldest first. */
    val chapters: List<ChapterRef> = emptyList(),
    val updatedAt: Long = 0,
) {
    val summary get() = NovelSummary(url, title, cover)

    fun toDetails() = NovelDetails(
        url = url, title = title, altTitle = null, cover = cover, status = null, info = emptyList(),
        synopsis = emptyList(), genres = emptyList(), rating = null, chapters = chapters,
    )
}

@Serializable
private data class SavedNovelsFile(val novels: List<SavedNovel> = emptyList())

object SavedNovels {
    private val file get() = File(AppDirs.root, "downloads.json")

    var entries by mutableStateOf(load())
        private set

    private fun load(): Map<String, SavedNovel> = runCatching {
        AppJson.decodeFromString(SavedNovelsFile.serializer(), file.readText()).novels.associateBy { it.url }
    }.getOrElse { emptyMap() }

    private fun save() {
        runCatching { file.writeAtomically(AppJson.encodeToString(SavedNovelsFile.serializer(), SavedNovelsFile(entries.values.toList()))) }
    }

    operator fun get(url: String): SavedNovel? = entries[url]

    @Synchronized
    fun remember(novel: NovelSummary, chapters: List<ChapterRef>) {
        val old = entries[novel.url]
        entries = entries + (novel.url to SavedNovel(
            url = novel.url,
            title = novel.title.ifBlank { old?.title.orEmpty() },
            cover = novel.cover ?: old?.cover,
            chapters = chapters.ifEmpty { old?.chapters.orEmpty() },
            updatedAt = System.currentTimeMillis(),
        ))
        save()
    }

    @Synchronized
    fun forget(url: String) {
        entries = entries - url
        save()
    }
}

/**
 * Saves chapters to disk one at a time, across all novels, gently, so they can be read offline.
 * Kept free of UI code so other front ends (a phone app) can reuse it.
 */
class Downloads(private val source: KolSource) {
    data class Progress(
        val novel: NovelSummary,
        val done: Int,
        val total: Int,
        val failed: List<ChapterRef> = emptyList(),
        /** Label of the chapter being fetched right now. */
        val current: String? = null,
    ) {
        val finished get() = done + failed.size >= total
    }

    private data class Task(val novelUrl: String, val ref: ChapterRef)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Task>()
    private var worker: Job? = null

    /** Per novel, newest request first. Finished entries stay until cleared so the result can be seen. */
    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set

    /** Chapter urls waiting or being fetched. */
    var queued by mutableStateOf<Set<String>>(emptySet())
        private set

    val activeCount: Int get() = progress.values.count { !it.finished }

    fun isRunning(novelUrl: String) = progress[novelUrl]?.finished == false

    /**
     * Queues [pick] (chapters of [novel]); [all] is the novel's full chapter list, remembered for offline use.
     * Returns how many chapters were added (already saved or queued ones are skipped).
     */
    @Synchronized
    fun enqueue(novel: NovelSummary, all: List<ChapterRef>, pick: List<ChapterRef>): Int {
        SavedNovels.remember(novel, all)
        val waiting = queued
        val todo = pick.filter { !ChapterStore.has(it.url) && it.url !in waiting }.distinctBy { it.url }
        if (todo.isEmpty()) return 0
        todo.forEach { queue.addLast(Task(novel.url, it)) }
        queued = waiting + todo.map { it.url }
        val old = progress[novel.url]?.takeIf { !it.finished }
        progress = (progress - novel.url) + (novel.url to (old?.copy(total = old.total + todo.size) ?: Progress(novel, 0, todo.size)))
        if (worker == null) worker = scope.launch { work() }
        return todo.size
    }

    @Synchronized
    fun cancel(novelUrl: String) {
        val dropped = queue.filter { it.novelUrl == novelUrl }
        queue.removeAll(dropped)
        queued = queued - dropped.map { it.ref.url }.toSet()
        progress = progress - novelUrl
    }

    fun retryFailed(novelUrl: String) {
        val p = progress[novelUrl] ?: return
        if (p.failed.isEmpty()) return
        synchronized(this) { progress = progress - novelUrl }
        enqueue(p.novel, emptyList(), p.failed)
    }

    @Synchronized
    fun clearFinished() {
        progress = progress.filterValues { !it.finished }
    }

    private suspend fun work() {
        while (true) {
            val task = next() ?: return
            val ok = fetch(task.ref.url)
            finish(task, ok)
            delay(350)
        }
    }

    @Synchronized
    private fun next(): Task? {
        val task = queue.removeFirstOrNull()
        if (task == null) {
            worker = null
            return null
        }
        progress[task.novelUrl]?.let { progress = progress + (task.novelUrl to it.copy(current = task.ref.label)) }
        return task
    }

    @Synchronized
    private fun finish(task: Task, ok: Boolean) {
        queued = queued - task.ref.url
        val p = progress[task.novelUrl] ?: return // cancelled meanwhile
        progress = progress + (task.novelUrl to p.copy(
            done = if (ok) p.done + 1 else p.done,
            failed = if (ok) p.failed else p.failed + task.ref,
            current = null,
        ))
    }

    /** A few tries with a growing pause, since the site sometimes drops a request. */
    private suspend fun fetch(url: String): Boolean {
        repeat(3) { attempt ->
            try {
                if (ChapterStore.save(source.chapter(url))) return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(1500L * (attempt + 1))
        }
        return false
    }
}
