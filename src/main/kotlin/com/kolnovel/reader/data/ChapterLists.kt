package com.kolnovel.reader.data

/**
 * Each novel's chapters in reading order, used to find the previous and next chapter.
 * The chapter pages' own prev/next links follow posting order and can skip many chapters.
 */
object ChapterLists {
    private val cache = object : LinkedHashMap<String, List<ChapterRef>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChapterRef>>?) = size > 30
    }

    @Synchronized
    fun remember(novelUrl: String, chapters: List<ChapterRef>) {
        if (chapters.isNotEmpty()) cache[novelUrl] = chapters
    }

    /** From memory or the offline copy; no network. */
    @Synchronized
    fun cached(novelUrl: String): List<ChapterRef>? =
        cache[novelUrl] ?: SavedNovels[novelUrl]?.chapters?.takeIf { it.isNotEmpty() }
            ?.let(KolSource::readingOrder)?.also { cache[novelUrl] = it }

    /** Loads the novel page when the list isn't known yet; null when that fails (offline). */
    suspend fun get(source: KolSource, novelUrl: String): List<ChapterRef>? =
        cached(novelUrl) ?: runCatching { source.details(novelUrl).chapters }.getOrNull()?.also { remember(novelUrl, it) }

    /** (previous, next) around [chapterUrl], or null when the chapter isn't in [list]. */
    fun neighbours(list: List<ChapterRef>, chapterUrl: String): Pair<String?, String?>? {
        val i = list.indexOfFirst { it.url == chapterUrl }
        if (i < 0) return null
        return list.getOrNull(i - 1)?.url to list.getOrNull(i + 1)?.url
    }
}
