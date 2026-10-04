package com.kolnovel.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** %APPDATA%\KolNovelReader on Windows, ~/.kolnovelreader elsewhere. Deleting the app folder keeps it. */
object AppDirs {
    val root: File by lazy {
        val appData = System.getenv("APPDATA")
        val dir = if (!appData.isNullOrBlank()) File(appData, "KolNovelReader")
        else File(System.getProperty("user.home"), ".kolnovelreader")
        dir.apply { mkdirs() }
    }
    val chapters: File get() = File(root, "chapters").apply { mkdirs() }
    val images: File get() = File(root, "images").apply { mkdirs() }
}

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Writes through a temp file so a crash mid-write never leaves a broken file. */
fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        delete()
        tmp.renameTo(this)
    }
}

fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

// ------------------------------------------------------------------------------------ settings

@Serializable
data class Settings(
    val theme: String = "black",
    val accent: String = "maroon",
    /** "#RRGGBB" when the user typed their own accent, else empty. */
    val customAccent: String = "",
    /** 0..1, how see-through the glass cards are. */
    val glassOpacity: Float = 0.55f,
    val liveBackground: Boolean = true,
    val readerFontSize: Int = 20,
    val readerLineHeight: Float = 1.9f,
    val readerWidth: Int = 820,
    val readerFont: String = "cairo",
    val readerJustify: Boolean = true,
    val readerParagraphGap: Int = 14,
    /** Empty = follow the app theme; otherwise one of the theme ids. */
    val readerTheme: String = "",
    val gridColumnsMin: Int = 170,
    val windowX: Int = -1,
    val windowY: Int = -1,
    val windowW: Int = 1280,
    val windowH: Int = 820,
    val windowMaximized: Boolean = true,
)

object SettingsStore {
    private val file get() = File(AppDirs.root, "settings.json")

    var settings by mutableStateOf(load())
        private set

    private fun load(): Settings = runCatching {
        AppJson.decodeFromString(Settings.serializer(), file.readText())
    }.getOrElse { Settings() }

    fun update(change: (Settings) -> Settings) {
        settings = change(settings)
        runCatching { file.writeAtomically(AppJson.encodeToString(Settings.serializer(), settings)) }
    }
}

// ------------------------------------------------------------------------------------- library

@Serializable
data class LibraryEntry(
    val url: String,
    val title: String,
    val cover: String? = null,
    val inLibrary: Boolean = false,
    val favorite: Boolean = false,
    val lastChapterUrl: String? = null,
    val lastChapterTitle: String? = null,
    /** Index of the first paragraph on screen when the user left the chapter. */
    val lastParagraph: Int = 0,
    val lastReadAt: Long = 0,
    val addedAt: Long = 0,
    val readChapters: Set<String> = emptySet(),
    val knownChapters: Int = 0,
    val newChapters: Int = 0,
)

@Serializable
private data class LibraryFile(val entries: List<LibraryEntry> = emptyList())

object LibraryStore {
    private val file get() = File(AppDirs.root, "library.json")

    var entries by mutableStateOf(load())
        private set

    private fun load(): Map<String, LibraryEntry> = runCatching {
        AppJson.decodeFromString(LibraryFile.serializer(), file.readText()).entries.associateBy { it.url }
    }.getOrElse { emptyMap() }

    private fun save() {
        runCatching {
            file.writeAtomically(AppJson.encodeToString(LibraryFile.serializer(), LibraryFile(entries.values.toList())))
        }
    }

    operator fun get(url: String): LibraryEntry? = entries[url]

    @Synchronized
    fun edit(url: String, title: String, cover: String?, change: (LibraryEntry) -> LibraryEntry) {
        val old = entries[url] ?: LibraryEntry(url = url, title = title, cover = cover)
        val updated = change(old.copy(title = title.ifBlank { old.title }, cover = cover ?: old.cover))
        entries = entries + (url to updated)
        save()
    }

    fun toggleLibrary(novel: NovelSummary) = edit(novel.url, novel.title, novel.cover) {
        it.copy(inLibrary = !it.inLibrary, addedAt = if (!it.inLibrary) System.currentTimeMillis() else it.addedAt)
    }

    fun toggleFavorite(novel: NovelSummary) = edit(novel.url, novel.title, novel.cover) {
        it.copy(favorite = !it.favorite, inLibrary = it.inLibrary || !it.favorite)
    }

    fun markRead(novelUrl: String, chapterUrls: Collection<String>, read: Boolean) {
        val e = entries[novelUrl] ?: return
        edit(novelUrl, e.title, e.cover) {
            it.copy(readChapters = if (read) it.readChapters + chapterUrls else it.readChapters - chapterUrls.toSet())
        }
    }

    fun saveProgress(novelUrl: String, title: String, cover: String?, chapter: ChapterContent, paragraph: Int, finished: Boolean) =
        edit(novelUrl, title, cover) {
            it.copy(
                lastChapterUrl = chapter.url,
                lastChapterTitle = chapter.title,
                lastParagraph = paragraph,
                lastReadAt = System.currentTimeMillis(),
                readChapters = if (finished) it.readChapters + chapter.url else it.readChapters,
            )
        }

    fun noteChapterCount(novelUrl: String, count: Int) {
        val e = entries[novelUrl] ?: return
        if (e.knownChapters == count) return
        edit(novelUrl, e.title, e.cover) {
            val fresh = if (it.knownChapters in 1 until count) count - it.knownChapters else 0
            it.copy(knownChapters = count, newChapters = if (it.inLibrary) it.newChapters + fresh else 0)
        }
    }

    fun clearNew(novelUrl: String) {
        val e = entries[novelUrl] ?: return
        if (e.newChapters != 0) edit(novelUrl, e.title, e.cover) { it.copy(newChapters = 0) }
    }

    fun removeHistory(novelUrl: String) {
        val e = entries[novelUrl] ?: return
        edit(novelUrl, e.title, e.cover) { it.copy(lastReadAt = 0, lastChapterUrl = null, lastChapterTitle = null) }
    }
}

// ------------------------------------------------------------------------------- saved chapters

/** Chapters the reader has opened or downloaded, kept as JSON so they open offline. */
object ChapterStore {
    private fun fileFor(url: String) = File(AppDirs.chapters, sha1(url) + ".json")

    fun load(url: String): ChapterContent? = runCatching {
        AppJson.decodeFromString(ChapterContent.serializer(), fileFor(url).readText())
    }.getOrNull()

    fun save(chapter: ChapterContent) {
        if (chapter.paragraphs.isEmpty()) return
        runCatching { fileFor(chapter.url).writeAtomically(AppJson.encodeToString(ChapterContent.serializer(), chapter)) }
    }

    fun has(url: String) = fileFor(url).exists()

    fun delete(urls: Collection<String>) = urls.forEach { fileFor(it).delete() }

    fun sizeBytes(): Long = AppDirs.chapters.listFiles()?.sumOf { it.length() } ?: 0
}
