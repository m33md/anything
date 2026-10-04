package com.kolnovel.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reads kolnovel.com (ملوك الروايات). The site runs the Themesia "lightnovel" WordPress theme,
 * and its REST API is closed, so everything is scraped from the HTML pages.
 */
class KolSource(private val client: OkHttpClient = defaultClient()) {

    @Volatile
    private var filterCache: FilterOptions? = null

    suspend fun home(): HomePage = parseHome(get(BASE_URL))

    suspend fun browse(filter: BrowseFilter, page: Int): ListPage = parseList(get(browseUrl(filter, page)))

    suspend fun search(query: String, page: Int): ListPage {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$BASE_URL/?s=$q" else "$BASE_URL/page/$page/?s=$q"
        return parseList(get(url))
    }

    /** [path] is a site link such as https://kolnovel.com/genre/action/ or /type/chinese/. */
    suspend fun listing(path: String, page: Int): ListPage {
        val base = absolute(path).trimEnd('/')
        return parseList(get(if (page <= 1) "$base/" else "$base/page/$page/"))
    }

    suspend fun filters(): FilterOptions {
        filterCache?.let { return it }
        val options = runCatching { parseFilters(get("$BASE_URL/series/")) }.getOrNull()
        if (options != null && options.genres.isNotEmpty()) filterCache = options
        return options ?: FilterOptions.Fallback
    }

    suspend fun details(url: String): NovelDetails = parseDetails(get(url), url)

    suspend fun chapter(url: String): ChapterContent = parseChapter(get(url), url)

    private suspend fun get(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar,en;q=0.8")
            .header("Referer", "$BASE_URL/")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SiteException(
                    when (response.code) {
                        403, 503 -> if (body.contains("cf-chl") || body.contains("Just a moment"))
                            "الموقع طلب تحقق (Cloudflare). افتح الموقع في المتصفح مرة ثم حاول مجددًا."
                        else "الموقع رفض الطلب (${response.code})."
                        404 -> "الصفحة غير موجودة على الموقع (404)."
                        else -> "خطأ من الموقع (${response.code})."
                    }
                )
            }
            Jsoup.parse(body, url)
        }
    }

    companion object {
        const val BASE_URL = "https://kolnovel.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun absolute(url: String): String = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> BASE_URL + url
            else -> "$BASE_URL/$url"
        }

        fun browseUrl(filter: BrowseFilter, page: Int): String {
            val params = buildList {
                if (page > 1) add("page=$page")
                // Filter values come from the site's own form and are already URL-encoded.
                filter.genres.forEach { add("genre%5B%5D=$it") }
                filter.types.forEach { add("type%5B%5D=$it") }
                if (filter.status.isNotEmpty()) add("status=${filter.status}")
                add("order=${filter.order}")
            }
            return "$BASE_URL/series/?" + params.joinToString("&")
        }

        // ---------------------------------------------------------------- parsing

        fun parseHome(doc: Document): HomePage {
            val featured = doc.select(".slide-item").mapNotNull { slide ->
                val link = slide.selectFirst(".poster a[href], .ellipsis a[href]") ?: return@mapNotNull null
                NovelSummary(
                    url = link.absUrl("href"),
                    title = slide.selectFirst(".ellipsis")?.text()?.trim().orEmpty()
                        .ifEmpty { slide.selectFirst("img")?.attr("title").orEmpty() },
                    cover = imageUrl(slide.selectFirst(".poster img") ?: slide.selectFirst("img")),
                    excerpt = slide.selectFirst(".excerpt .story")?.text()?.trim()?.ifEmpty { null },
                    rating = slide.selectFirst(".site-vote span span")?.text()?.trim(),
                    genres = slide.select(".slid-gen a, .cast a").map { it.text().trim() }.filter { it.isNotEmpty() },
                )
            }.distinctBy { it.url }

            val popular = doc.select(".hotoday .inhotoday > a[href]").map { a ->
                NovelSummary(
                    url = a.absUrl("href"),
                    title = a.selectFirst(".todtitle")?.text()?.trim() ?: a.attr("title"),
                    cover = imageUrl(a.selectFirst("img")),
                    genres = a.select(".todgen a").map { it.text().trim() },
                    rating = a.selectFirst(".todnum")?.text()?.trim(),
                    status = statusLabel(a.selectFirst(".todstat")?.text()),
                    latestChapter = a.selectFirst(".todchap")?.text()?.trim(),
                )
            }.distinctBy { it.url }

            val latest = doc.select(".utao .uta").mapNotNull { uta ->
                val link = uta.selectFirst(".luf a.series[href], .imgu a[href]") ?: return@mapNotNull null
                val novel = NovelSummary(
                    url = link.absUrl("href"),
                    title = uta.selectFirst(".luf h3, .luf h4")?.text()?.trim() ?: link.attr("title"),
                    cover = imageUrl(uta.selectFirst(".imgu img")),
                )
                val chapters = uta.select(".luf ul li").mapNotNull { li ->
                    val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                    LatestChapter(a.absUrl("href"), a.text().trim(), li.selectFirst("span")?.text()?.trim())
                }
                LatestEntry(novel, chapters)
            }.distinctBy { it.novel.url }

            return HomePage(featured, popular, latest)
        }

        /** Series list, search results, genre and type pages all use article.maindet cards. */
        fun parseList(doc: Document): ListPage {
            val cards = doc.select(".listupd article.maindet").mapNotNull { card ->
                val link = card.selectFirst(".mdinfo h2 a[href], .mdthumb a[href]") ?: return@mapNotNull null
                NovelSummary(
                    url = link.absUrl("href"),
                    title = link.attr("title").ifBlank { link.text() }.trim(),
                    cover = imageUrl(card.selectFirst(".mdthumb img")),
                    genres = card.select(".mdgenre a").map { it.text().removePrefix("#").trim() },
                    excerpt = card.selectFirst(".contexcerpt")?.text()?.trim()?.ifEmpty { null },
                    rating = card.selectFirst(".mdminf")?.text()?.trim()?.ifEmpty { null },
                    latestChapter = card.selectFirst(".nchapter")?.text()?.trim()?.ifEmpty { null },
                )
            }.toMutableList()
            // Older layout of the same theme, in case a page still uses it.
            if (cards.isEmpty()) {
                doc.select(".listupd .bs .bsx > a[href]").forEach { a ->
                    cards += NovelSummary(
                        url = a.absUrl("href"),
                        title = a.selectFirst(".tt")?.ownText()?.trim().orEmpty().ifEmpty { a.attr("title") },
                        cover = imageUrl(a.selectFirst("img")),
                    )
                }
            }
            val hasNext = doc.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
            return ListPage(cards.distinctBy { it.url }, hasNext)
        }

        fun parseFilters(doc: Document): FilterOptions {
            fun options(name: String): List<FilterOptions.Option> =
                doc.select("input[name=$name]").map { input ->
                    val id = input.id()
                    val label = (if (id.isNotEmpty()) doc.selectFirst("label[for=$id]") else null)
                        ?: input.nextElementSibling()
                    FilterOptions.Option(input.attr("value"), label?.text()?.trim().orEmpty())
                }.filter { it.label.isNotEmpty() }
            val statuses = options("status").map { it.copy(label = statusLabel(it.label) ?: it.label) }
            return FilterOptions(
                genres = options("genre[]"),
                types = options("type[]").filter { it.label.length > 1 },
                statuses = statuses.ifEmpty { FilterOptions.Fallback.statuses },
                orders = options("order").ifEmpty { FilterOptions.Fallback.orders },
            )
        }

        fun parseDetails(doc: Document, url: String): NovelDetails {
            val info = doc.select(".sertoauth .serl").mapNotNull { row ->
                val name = row.selectFirst(".sername")?.text()?.trim()?.removeSuffix(":") ?: return@mapNotNull null
                val value = row.selectFirst(".serval")?.text()?.trim().orEmpty()
                if (value.isEmpty() || value.contains("Views")) null else name to value
            }
            val synopsis = doc.select(".sersys p").map { it.text().trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOfNotNull(doc.selectFirst(".sersys")?.text()?.trim()?.ifEmpty { null }) }
            val chapters = doc.select(".eplister ul li").mapNotNull { li ->
                val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                val number = cleanChapterNumber(a.selectFirst(".epl-num")?.text().orEmpty())
                ChapterRef(
                    url = a.absUrl("href"),
                    number = number.ifEmpty { a.text().trim() },
                    title = a.selectFirst(".epl-title")?.text()?.trim()?.ifEmpty { null },
                    date = a.selectFirst(".epl-date")?.text()?.trim()?.ifEmpty { null },
                    id = li.attr("data-ID").ifEmpty { li.attr("data-id") }.toLongOrNull(),
                )
            }.distinctBy { it.url }.reversed()
            return NovelDetails(
                url = url,
                title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty(),
                altTitle = doc.selectFirst(".sertoinfo .alter")?.text()?.trim()?.ifEmpty { null },
                cover = imageUrl(doc.selectFirst(".sertothumb img")),
                status = statusLabel(doc.selectFirst(".sertostat span")?.text()),
                info = info,
                synopsis = synopsis,
                genres = doc.select(".sertogenre a").map { it.text().trim() },
                rating = doc.selectFirst(".custom-rating-value")?.text()?.trim()
                    ?: doc.selectFirst(".sertorating .num, .rating .num")?.text()?.trim(),
                chapters = chapters,
            )
        }

        fun parseChapter(doc: Document, url: String): ChapterContent {
            val content = doc.getElementById("kol_content") ?: doc.selectFirst(".epcontent")
                ?: throw SiteException("لم أجد نص الفصل في الصفحة.")
            val hidden = hiddenClasses(doc)
            content.select("script, style, noscript, iframe, ins, dialog, button, aside, blockquote, img, svg").remove()
            content.select("[class*=kol-], [class*=shola], .kln, .announ, [id*=_ad_]").remove()
            content.select("*").filter { el -> el.classNames().any { it in hidden } || isHiddenStyle(el.attr("style")) }
                .forEach { it.remove() }

            val paragraphs = mutableListOf<String>()
            val blocks = content.select("p, h2, h3, h4").ifEmpty { listOf(content) }
            for (block in blocks) {
                // Lines split by <br> inside one paragraph become separate paragraphs.
                val html = block.html().replace(Regex("(?i)<br\\s*/?>"), "\n")
                Jsoup.parseBodyFragment(html).body().wholeText()
                    .split('\n')
                    .map { it.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotEmpty() && it != "---" && it != "***" }
                    .forEach { paragraphs += it }
            }

            val crumbs = doc.select(".ts-breadcrumb [itemprop=itemListElement] a")
            val novelLink = crumbs.getOrNull(1)
            return ChapterContent(
                url = url,
                title = doc.selectFirst(".epheader h1.entry-title, h1.entry-title")?.text()?.trim().orEmpty(),
                novelTitle = novelLink?.text()?.trim(),
                novelUrl = novelLink?.absUrl("href"),
                paragraphs = paragraphs.dropTrailingSiteNotes(),
                prevUrl = doc.selectFirst("a[rel=prev]")?.absUrl("href")?.ifEmpty { null }?.takeUnless { isSeriesLink(it) },
                nextUrl = doc.selectFirst("a[rel=next]")?.absUrl("href")?.ifEmpty { null }?.takeUnless { isSeriesLink(it) },
            )
        }

        /**
         * The site hides watermark text inside chapters with random class names whose CSS
         * (in a <style> in the page) makes them invisible. Find those classes so they can be dropped.
         */
        fun hiddenClasses(doc: Document): Set<String> {
            val result = mutableSetOf<String>()
            val rule = Regex("([^{}]+)\\{([^}]*)\\}")
            for (style in doc.select("style")) {
                for (match in rule.findAll(style.data())) {
                    val body = match.groupValues[2].replace(" ", "").lowercase()
                    val hides = "opacity:0" in body || "display:none" in body ||
                        "height:0.1px" in body || "text-indent:-9" in body || "visibility:hidden" in body
                    if (!hides) continue
                    Regex("\\.([A-Za-z0-9_-]+)").findAll(match.groupValues[1]).forEach { result += it.groupValues[1] }
                }
            }
            return result
        }

        private fun isHiddenStyle(style: String): Boolean {
            val s = style.replace(" ", "").lowercase()
            return "display:none" in s || "opacity:0;" in s || s.endsWith("opacity:0") || "visibility:hidden" in s
        }

        private fun isSeriesLink(url: String) = url.contains("/series/") || url.trimEnd('/') == BASE_URL

        private val SITE_NOTES = listOf("ترجمة موقع ملوك الروايات", "اشترك الان", "لا مزيد من الإعلانات", "kolnovel")

        private fun List<String>.dropTrailingSiteNotes(): List<String> {
            var end = size
            while (end > 0 && SITE_NOTES.any { this[end - 1].contains(it, ignoreCase = true) }) end--
            return subList(0, end)
        }

        fun cleanChapterNumber(raw: String): String =
            raw.replace(Regex("INFO\\d*"), " ").replace(Regex("\\s+"), " ").trim()

        fun statusLabel(raw: String?): String? = when (raw?.trim()?.lowercase()) {
            null, "" -> null
            "ongoing" -> "مستمرة"
            "completed" -> "مكتملة"
            "hiatus" -> "متوقفة"
            "dropped" -> "متروكة"
            else -> raw.trim()
        }

        fun imageUrl(img: Element?): String? {
            if (img == null) return null
            val candidates = listOf("data-src", "data-lazy-src", "src")
            for (attr in candidates) {
                val value = img.absUrl(attr)
                if (value.isNotEmpty() && !value.startsWith("data:")) return value
            }
            return null
        }
    }
}
