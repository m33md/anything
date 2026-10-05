package com.kolnovel.reader

import com.kolnovel.reader.data.ChapterRef
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SavedNovels
import com.kolnovel.reader.data.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadsTest {
    private val failing = "https://kolnovel.com/broken/"

    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    private val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
    /** How many requests answer "429 too many requests" before the fake site calms down. */
    private val tooFastLeft = java.util.concurrent.atomic.AtomicInteger()

    private fun client() = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val now = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now, ::maxOf)
        try { Thread.sleep(60) } finally { inFlight.decrementAndGet() }
        val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        if (tooFastLeft.getAndDecrement() > 0) {
            response.code(429).message("Too Many Requests").body("".toResponseBody(null)).build()
        } else if (url == failing) {
            response.code(500).message("Error").body("".toResponseBody(null)).build()
        } else {
            val html = """<html><body><div class="epheader"><h1 class="entry-title">فصل</h1></div>
                <div id="kol_content"><p>هذه فقرة أولى من نص تجريبي.</p><p>وهذه فقرة ثانية تكمل الفصل.</p></div></body></html>"""
            response.code(200).message("OK").body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }
    }.build()

    private fun waitUntil(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(what(), "timed out")
    }

    @Test
    fun queuesSavesRemembersAndReportsFailures() {
        System.setProperty("user.home", File("build/home-downloads").absolutePath)
        val downloads = Downloads(KolSource(client()))
        val novel = NovelSummary("https://kolnovel.com/series/test-novel/", "رواية تجريبية")
        val chapters = (1..4).map { ChapterRef("https://kolnovel.com/test-novel-$it/", "الفصل $it") } +
            ChapterRef(failing, "الفصل 5")
        ChapterStore.delete(chapters.map { it.url })

        assertEquals(2, downloads.enqueue(novel, chapters, chapters.take(2)))
        // Asking again for a queued chapter adds only the new ones.
        assertEquals(3, downloads.enqueue(novel, chapters, chapters))
        assertEquals(chapters, SavedNovels[novel.url]?.chapters)

        waitUntil { downloads.progress[novel.url]?.finished == true }
        val p = downloads.progress.getValue(novel.url)
        assertEquals(4, p.done)
        assertEquals(listOf(failing), p.failed.map { it.url })
        assertEquals(4, ChapterStore.countSaved(chapters.map { it.url }))
        assertTrue(downloads.queued.isEmpty())

        // Already saved chapters are not fetched again.
        assertEquals(0, downloads.enqueue(novel, chapters, chapters.take(4)))

        downloads.clearFinished()
        ChapterStore.delete(chapters.map { it.url })
        assertEquals(0, ChapterStore.countSaved(chapters.map { it.url }))
        SavedNovels.forget(novel.url)
    }

    @Test
    fun downloadsSeveralAtOnceAndSlowsDownWhenTheSiteAsks() {
        System.setProperty("user.home", File("build/home-downloads").absolutePath)
        val novel = NovelSummary("https://kolnovel.com/series/test-fast/", "رواية سريعة")
        val chapters = (1..12).map { ChapterRef("https://kolnovel.com/test-fast-$it/", "الفصل $it") }
        ChapterStore.delete(chapters.map { it.url })
        SettingsStore.update { it.copy(downloadsAtOnce = 5) }

        val fast = Downloads(KolSource(client()))
        maxInFlight.set(0)
        fast.enqueue(novel, chapters, chapters.take(6))
        waitUntil { fast.progress[novel.url]?.finished == true }
        assertTrue(maxInFlight.get() in 2..5, "at once: ${maxInFlight.get()}")
        assertEquals(null, fast.notice)

        // The site starts answering 429: everything still arrives, one at a time, and the user is told.
        tooFastLeft.set(3)
        fast.enqueue(novel, chapters, chapters)
        waitUntil { fast.progress[novel.url]?.finished == true }
        assertEquals(12, ChapterStore.countSaved(chapters.map { it.url }))
        assertTrue(fast.notice != null)

        SettingsStore.update { it.copy(downloadsAtOnce = 2) }
        ChapterStore.delete(chapters.map { it.url })
        SavedNovels.forget(novel.url)
    }
}
