package com.kolnovel.reader.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/** One novel in the download queue. [pending] is what is still to fetch, oldest chapter first. */
@Serializable
data class DownloadJob(
    val novelUrl: String,
    val title: String,
    val cover: String? = null,
    val pending: List<ChapterRef> = emptyList(),
    val total: Int = 0,
    val done: Int = 0,
    val failed: List<ChapterRef> = emptyList(),
    val addedAt: Long = 0,
) {
    val finished: Boolean get() = pending.isEmpty()
}

@Serializable
private data class QueueFile(val jobs: List<DownloadJob> = emptyList(), val paused: Boolean = false)

/**
 * Saves chapters for reading without internet, a few at a time (Settings > downloads) and backing
 * off by itself when the site starts refusing requests. The queue is kept on disk, so closing the app and opening it again carries on.
 * While it runs, [DownloadService] keeps the app alive with a notification.
 */
object Downloads {
    private lateinit var appContext: Context
    private lateinit var source: KolSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var worker: Job? = null
    private var lastPersist = 0L

    var jobs by mutableStateOf<List<DownloadJob>>(emptyList())
        private set
    var paused by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var current by mutableStateOf<ChapterRef?>(null)
        private set
    /** Why the queue stopped by itself (no internet, Cloudflare...), shown on the downloads screen. */
    var notice by mutableStateOf<String?>(null)
        private set

    val hasPending: Boolean get() = jobs.any { !it.finished }

    private val file get() = File(AppDirs.root, "download_queue.json")

    fun init(context: Context, kolSource: KolSource) {
        appContext = context.applicationContext
        source = kolSource
        val saved = runCatching { AppJson.decodeFromString(QueueFile.serializer(), file.readText()) }.getOrNull()
        if (saved != null) {
            jobs = saved.jobs.filter { !it.finished || it.failed.isNotEmpty() }
            paused = saved.paused
        }
    }

    /** Called when the app comes to the front: picks up a queue left from last time. */
    fun resumeIfPending() {
        if (!paused && hasPending) ensureRunning()
    }

    /** Adds chapters to the queue; returns how many were not saved already. */
    fun enqueue(novel: NovelSummary, chapters: List<ChapterRef>, details: NovelDetails? = null): Int {
        details?.let { NovelStore.save(it, novel.cover) }
        val todo = chapters.filterNot { ChapterStore.has(it.url) }.distinctBy { it.url }
        if (todo.isEmpty()) return 0
        synchronized(lock) {
            val existing = jobs.find { it.novelUrl == novel.url }
            if (existing == null) {
                jobs = jobs + DownloadJob(
                    novelUrl = novel.url, title = novel.title, cover = novel.cover,
                    pending = todo, total = todo.size, addedAt = System.currentTimeMillis(),
                )
            } else {
                val base = if (existing.finished) existing.copy(total = 0, done = 0, failed = emptyList()) else existing
                val known = base.pending.map { it.url }.toHashSet()
                val add = todo.filter { it.url !in known }
                val addUrls = add.map { it.url }.toHashSet()
                replace(
                    base.copy(
                        pending = base.pending + add,
                        total = base.total + add.size,
                        failed = base.failed.filter { it.url !in addUrls },
                        cover = base.cover ?: novel.cover,
                    )
                )
            }
            paused = false
            notice = null
            persist(force = true)
        }
        ensureRunning()
        return todo.size
    }

    fun pause() {
        synchronized(lock) {
            paused = true
            worker?.cancel()
            persist(force = true)
        }
    }

    fun resume() {
        synchronized(lock) {
            paused = false
            notice = null
            persist(force = true)
        }
        ensureRunning()
    }

    fun cancel(novelUrl: String) {
        synchronized(lock) {
            jobs = jobs.filterNot { it.novelUrl == novelUrl }
            persist(force = true)
        }
    }

    fun retryFailed(novelUrl: String) {
        synchronized(lock) {
            val j = jobs.find { it.novelUrl == novelUrl } ?: return
            if (j.failed.isEmpty()) return
            replace(j.copy(pending = j.pending + j.failed, failed = emptyList(), done = j.done, total = j.total))
            paused = false
            notice = null
            persist(force = true)
        }
        ensureRunning()
    }

    fun clearFinished() {
        synchronized(lock) {
            jobs = jobs.filterNot { it.finished }
            persist(force = true)
        }
    }

    fun isQueued(chapterUrl: String): Boolean = jobs.any { j -> j.pending.any { it.url == chapterUrl } }

    // ------------------------------------------------------------------------------- the worker

    private fun ensureRunning() {
        synchronized(lock) {
            if (worker?.isActive == true || paused || !hasPending) return
            running = true
            worker = scope.launch { runQueue() }
        }
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
        }
    }

    private sealed interface Outcome {
        data object Saved : Outcome
        data class Failed(val why: String) : Outcome
        /** The site is answering "too many requests": try the chapter again later, with fewer at once. */
        data object Throttled : Outcome
        /** Something that would fail every chapter (no internet, Cloudflare): stop the queue. */
        data class Stop(val why: String) : Outcome
    }

    /** Chapters being fetched right now, so parallel lanes never take the same one. */
    private val inFlight = HashSet<String>()

    /**
     * How many chapters actually download at once. Starts at the setting, halves when the site
     * starts refusing requests, and climbs back by one after a run of clean downloads.
     */
    var lanesNow by mutableStateOf(0)
        private set
    private var cleanStreak = 0
    private var throttleStreak = 0

    private suspend fun runQueue() {
        val wanted = SettingsStore.settings.downloadLanes.coerceIn(1, 10)
        synchronized(lock) {
            lanesNow = wanted
            cleanStreak = 0
            throttleStreak = 0
            inFlight.clear()
        }
        try {
            coroutineScope {
                repeat(wanted) { lane -> launch { runLane(lane) } }
            }
        } finally {
            synchronized(lock) {
                inFlight.clear()
                current = null
                running = false
                persist(force = true)
            }
        }
    }

    private suspend fun runLane(lane: Int) {
        // Stagger the start so ten lanes don't hit the site in the same instant.
        delay(lane * 250L)
        while (currentCoroutineContext().isActive && !paused) {
            if (lane >= lanesNow) {
                // This lane is switched off for now; wait in case the count climbs back up.
                if (!hasPending) break
                delay(2000)
                continue
            }
            if (SettingsStore.settings.wifiOnly && !onWifi()) {
                stopWith("التنزيل متوقف حتى تتصل بشبكة Wi-Fi (يمكنك تغيير ذلك من الإعدادات).")
                break
            }
            val next = synchronized(lock) {
                jobs.firstNotNullOfOrNull { j ->
                    if (j.finished) null else j.pending.firstOrNull { it.url !in inFlight }?.let { j.novelUrl to it }
                }?.also { inFlight += it.second.url }
            }
            if (next == null) {
                // Nothing left that another lane isn't already fetching.
                if (synchronized(lock) { inFlight.isEmpty() }) break
                delay(500)
                continue
            }
            val (novelUrl, ref) = next
            current = ref
            val outcome = try {
                if (ChapterStore.has(ref.url)) Outcome.Saved else fetch(novelUrl, ref)
            } finally {
                synchronized(lock) { inFlight -= ref.url }
            }
            when (outcome) {
                is Outcome.Stop -> {
                    stopWith(outcome.why)
                    break
                }
                Outcome.Throttled -> {
                    val streak = synchronized(lock) {
                        cleanStreak = 0
                        throttleStreak++
                        lanesNow = maxOf(1, lanesNow / 2)
                        throttleStreak
                    }
                    if (streak >= 8) {
                        stopWith("الموقع يرفض الطلبات الكثيرة الآن. انتظر قليلًا ثم اضغط \"استئناف\"، أو قلّل عدد الفصول في نفس الوقت من الإعدادات.")
                        break
                    }
                    delay(3000L * streak)
                    continue
                }
                else -> Unit
            }
            synchronized(lock) {
                val j = jobs.find { it.novelUrl == novelUrl }
                if (j != null && j.pending.any { it.url == ref.url }) {
                    val ok = outcome is Outcome.Saved
                    replace(
                        j.copy(
                            pending = j.pending.filterNot { it.url == ref.url },
                            done = if (ok) j.done + 1 else j.done,
                            failed = if (ok) j.failed else j.failed + ref,
                        )
                    )
                    persist(force = !hasPending)
                }
                if (outcome is Outcome.Saved) {
                    throttleStreak = 0
                    if (++cleanStreak >= 25 && lanesNow < SettingsStore.settings.downloadLanes.coerceIn(1, 10)) {
                        lanesNow++
                        cleanStreak = 0
                    }
                }
            }
            if (outcome is Outcome.Saved) delay(350)
        }
    }

    private suspend fun fetch(novelUrl: String, ref: ChapterRef): Outcome {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val chapter = source.chapter(ref.url)
                // Save under the lock and only while the novel is still queued: "delete from device"
                // cancels the job first, so a chapter finishing mid-delete must not be written back.
                val saved = synchronized(lock) {
                    if (jobs.none { it.novelUrl == novelUrl && it.pending.any { p -> p.url == ref.url } }) return Outcome.Failed("أُلغي")
                    ChapterStore.save(chapter)
                }
                return if (saved) Outcome.Saved else Outcome.Failed("الفصل فارغ أو مقفل على الموقع.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                if (e.message == KolSource.CLOUDFLARE_MESSAGE) return Outcome.Stop(e.message!!)
                // 429 = too many requests; 503/508 without a Cloudflare page = the server is shedding load.
                if (e.code == 429 || e.code == 503 || e.code == 508) return Outcome.Throttled
                last = e
            } catch (e: Exception) {
                last = e
            }
            delay(1500L shl attempt)
        }
        val e = last
        return if (e is SiteException) Outcome.Failed(e.message ?: "خطأ من الموقع")
        else Outcome.Stop("انقطع الاتصال بالإنترنت أو بالموقع. اضغط \"استئناف\" عندما يعود الاتصال.")
    }

    private fun stopWith(why: String) {
        synchronized(lock) {
            notice = why
            paused = true
            persist(force = true)
        }
    }

    private fun onWifi(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun replace(job: DownloadJob) {
        jobs = jobs.map { if (it.novelUrl == job.novelUrl) job else it }
    }

    /** Queues of whole novels can hold thousands of chapters, so the file is rewritten at most every few seconds. */
    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 4000) return
        lastPersist = now
        runCatching { file.writeAtomically(AppJson.encodeToString(QueueFile.serializer(), QueueFile(jobs, paused))) }
    }
}
