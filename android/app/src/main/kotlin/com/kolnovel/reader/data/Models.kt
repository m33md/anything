package com.kolnovel.reader.data

import kotlinx.serialization.Serializable

/** One novel card as it appears in the site's lists (series page, search, genres, home). */
@Serializable
data class NovelSummary(
    val url: String,
    val title: String,
    val cover: String? = null,
    val genres: List<String> = emptyList(),
    val excerpt: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val latestChapter: String? = null,
)

/** A home "latest updates" entry: the novel plus its newest chapters. */
data class LatestEntry(val novel: NovelSummary, val chapters: List<LatestChapter>)

data class LatestChapter(val url: String, val title: String, val time: String?)

data class HomePage(
    val featured: List<NovelSummary>,
    val popularToday: List<NovelSummary>,
    val latest: List<LatestEntry>,
)

data class ListPage(val items: List<NovelSummary>, val hasNext: Boolean)

@Serializable
data class ChapterRef(
    val url: String,
    val number: String,
    val title: String? = null,
    val date: String? = null,
    val id: Long? = null,
) {
    /** "الفصل 12 - عنوان" style label used everywhere in the UI. */
    val label: String
        get() = if (title.isNullOrBlank() || title == number) number else "$number - $title"
}

data class NovelDetails(
    val url: String,
    val title: String,
    val altTitle: String?,
    val cover: String?,
    val status: String?,
    val info: List<Pair<String, String>>,
    val synopsis: List<String>,
    val genres: List<String>,
    val rating: String?,
    /** Oldest first (the site lists newest first). */
    val chapters: List<ChapterRef>,
)

@Serializable
data class ChapterContent(
    val url: String,
    val title: String,
    val novelTitle: String? = null,
    val novelUrl: String? = null,
    val paragraphs: List<String>,
    val prevUrl: String? = null,
    val nextUrl: String? = null,
)

/** The choices of the site's own series filter (read from the /series/ page). */
data class FilterOptions(
    val genres: List<Option>,
    val types: List<Option>,
    val statuses: List<Option>,
    val orders: List<Option>,
) {
    data class Option(val value: String, val label: String)

    companion object {
        val Fallback = FilterOptions(
            genres = emptyList(),
            types = emptyList(),
            statuses = listOf(
                Option("", "الكل"), Option("ongoing", "مستمرة"),
                Option("completed", "مكتملة"), Option("hiatus", "متوقفة"),
            ),
            orders = listOf(
                Option("", "الافتراضي"), Option("update", "أخر التحديثات"),
                Option("latest", "أخر ما تم إضافته"), Option("popular", "الرائجة"),
                Option("rating", "التقييم"), Option("title", "A-Z"),
            ),
        )
    }
}

data class BrowseFilter(
    val genres: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val status: String = "",
    val order: String = "update",
)

/** [code] is the HTTP status when the site answered with an error page (0 otherwise). */
class SiteException(message: String, val code: Int = 0) : Exception(message)
