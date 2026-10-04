package com.kolnovel.reader

import com.kolnovel.reader.data.BrowseFilter
import com.kolnovel.reader.data.KolSource
import org.jsoup.Jsoup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the parsers on pages saved from kolnovel.com. The pages are not in the repo (they are the
 * site's content); save them into samples/ to run these, otherwise the tests are skipped.
 */
class ParsingTest {
    private fun sample(name: String, url: String) =
        File("samples/$name").takeIf { it.exists() }?.let { Jsoup.parse(it, "UTF-8", url) }

    @Test
    fun seriesList() {
        val doc = sample("series.html", "https://kolnovel.com/series/") ?: return
        val page = KolSource.parseList(doc)
        assertTrue(page.items.size >= 10, "cards: ${page.items.size}")
        assertTrue(page.hasNext)
        page.items.forEach {
            assertTrue(it.url.startsWith("https://kolnovel.com/series/"), it.url)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.cover?.startsWith("https://") == true)
        }
        val filters = KolSource.parseFilters(doc)
        assertTrue(filters.genres.size > 50)
        assertTrue(filters.orders.any { it.value == "update" })
        assertTrue(filters.statuses.any { it.value == "completed" })
    }

    @Test
    fun searchAndGenre() {
        val search = sample("search.html", "https://kolnovel.com/?s=x") ?: return
        assertTrue(KolSource.parseList(search).items.isNotEmpty())
        val genre = sample("genre.html", "https://kolnovel.com/genre/action/") ?: return
        val page = KolSource.parseList(genre)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.hasNext)
    }

    @Test
    fun home() {
        val doc = sample("home.html", "https://kolnovel.com/") ?: return
        val home = KolSource.parseHome(doc)
        assertTrue(home.latest.size >= 10, "latest: ${home.latest.size}")
        assertTrue(home.latest.all { it.chapters.isNotEmpty() })
        assertTrue(home.popularToday.isNotEmpty())
        assertTrue(home.featured.isNotEmpty())
    }

    @Test
    fun novelPage() {
        val url = "https://kolnovel.com/series/emperors-domination/"
        val doc = sample("novel.html", url) ?: return
        val d = KolSource.parseDetails(doc, url)
        assertTrue(d.title.isNotBlank())
        assertTrue(d.cover != null)
        assertTrue(d.synopsis.isNotEmpty())
        assertTrue(d.genres.isNotEmpty())
        assertTrue(d.chapters.size > 6000, "chapters: ${d.chapters.size}")
        // Oldest first, and the site's "INFO" junk is cleaned out of chapter numbers.
        assertEquals("الفصل 1", d.chapters.first().number)
        assertFalse(d.chapters.any { it.number.contains("INFO") })
    }

    @Test
    fun chapterPage() {
        val url = "https://kolnovel.com/shaag24emperors-dominationz435ggye-257691/"
        val doc = sample("chapter.html", url) ?: return
        val hidden = KolSource.hiddenClasses(doc)
        assertTrue(hidden.size >= 5, "hidden classes: $hidden")
        val c = KolSource.parseChapter(doc, url)
        assertTrue(c.title.isNotBlank())
        assertEquals("https://kolnovel.com/series/emperors-domination/", c.novelUrl)
        assertTrue(c.paragraphs.size > 20, "paragraphs: ${c.paragraphs.size}")
        assertTrue(c.prevUrl!!.contains("257445"))
        assertTrue(c.nextUrl!!.contains("257777"))
        // None of the invisible watermark paragraphs may survive.
        val hiddenTexts = doc.select(hidden.joinToString(",") { ".$it" }).map { it.text().trim() }.filter { it.length > 3 }.toSet()
        assertTrue(c.paragraphs.none { it in hiddenTexts })
    }

    @Test
    fun browseUrl() {
        val url = KolSource.browseUrl(BrowseFilter(genres = setOf("action"), status = "completed", order = "update"), 2)
        assertEquals("https://kolnovel.com/series/?page=2&genre%5B%5D=action&status=completed&order=update", url)
    }
}
