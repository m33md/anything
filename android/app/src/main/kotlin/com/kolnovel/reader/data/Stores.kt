package com.kolnovel.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** The app's private storage on the phone. Uninstalling the app removes it. */
object AppDirs {
    lateinit var root: File
        private set

    fun init(dir: File) {
        root = dir.apply { mkdirs() }
    }

    val chapters: File get() = File(root, "chapters").apply { mkdirs() }
    val images: File get() = File(root, "images").apply { mkdirs() }
    val novels: File get() = File(root, "novels").apply { mkdirs() }
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
    val readerFontSize: Int = 19,
    val readerLineHeight: Float = 1.8f,
    val readerFont: String = "cairo",
    val readerJustify: Boolean = true,
    val readerParagraphGap: Int = 12,
    /** Empty = follow the app theme; otherwise one of the theme ids. */
    val readerTheme: String = "",
    val readerKeepScreenOn: Boolean = true,
    /** Volume buttons scroll the chapter. */
    val readerVolumeKeys: Boolean = true,
    val gridColumnsMin: Int = 108,
    /** Download chapters only on Wi-Fi. */
    val wifiOnly: Boolean = false,
    /** Folder (picked through Android's file picker) the library is synced through; empty = off. */
    val syncTreeUri: String = "",
    /** Random id naming this device's file in the sync folder (same scheme as the PC app). */
    val deviceId: String = "",
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
    /** When "in library" or "favourite" last changed, so syncing keeps the newest choice. */
    val flagsChangedAt: Long = 0,
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

    /** Called after every local change (sync uses it to push the change to other devices). */
    var onChanged: (() -> Unit)? = null

    private fun save() {
        runCatching {
            file.writeAtomically(AppJson.encodeToString(LibraryFile.serializer(), LibraryFile(entries.values.toList())))
        }
    }

    /** Replaces the whole library with a merged copy from sync, without counting it as a local change. */
    @Synchronized
    fun replaceFromSync(merged: Map<String, LibraryEntry>) {
        if (merged == entries) return
        entries = merged
        save()
    }

    operator fun get(url: String): LibraryEntry? = entries[url]

    @Synchronized
    fun edit(url: String, title: String, cover: String?, change: (LibraryEntry) -> LibraryEntry) {
        val old = entries[url] ?: LibraryEntry(url = url, title = title, cover = cover)
        val updated = change(old.copy(title = title.ifBlank { old.title }, cover = cover ?: old.cover))
        entries = entries + (url to updated)
        save()
        onChanged?.invoke()
    }

    fun toggleLibrary(novel: NovelSummary) = edit(novel.url, novel.title, novel.cover) {
        it.copy(
            inLibrary = !it.inLibrary,
            addedAt = if (!it.inLibrary) System.currentTimeMillis() else it.addedAt,
            flagsChangedAt = System.currentTimeMillis(),
        )
    }

    fun toggleFavorite(novel: NovelSummary) = edit(novel.url, novel.title, novel.cover) {
        it.copy(favorite = !it.favorite, inLibrary = it.inLibrary || !it.favorite, flagsChangedAt = System.currentTimeMillis())
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
    /** Bumped on every save or delete so screens showing "saved" marks redraw. */
    var version by mutableIntStateOf(0)
        private set

    private fun fileFor(url: String) = File(AppDirs.chapters, sha1(url) + ".json")

    fun load(url: String): ChapterContent? = runCatching {
        AppJson.decodeFromString(ChapterContent.serializer(), fileFor(url).readText())
    }.getOrNull()

    /** True only when the chapter is on disk afterwards, so callers can count failed saves. */
    fun save(chapter: ChapterContent): Boolean {
        if (chapter.paragraphs.isEmpty()) return false
        val ok = runCatching {
            fileFor(chapter.url).writeAtomically(AppJson.encodeToString(ChapterContent.serializer(), chapter))
        }.isSuccess && has(chapter.url)
        bump()
        return ok
    }

    fun has(url: String) = fileFor(url).exists()

    fun sizeOf(urls: Collection<String>): Long = urls.sumOf { fileFor(it).length() }

    fun delete(urls: Collection<String>) {
        urls.forEach { fileFor(it).delete() }
        bump()
    }

    fun deleteAll() {
        AppDirs.chapters.listFiles()?.forEach { it.delete() }
        bump()
    }

    fun sizeBytes(): Long = AppDirs.chapters.listFiles()?.sumOf { it.length() } ?: 0

    @Synchronized
    private fun bump() {
        version++
    }
}

// ------------------------------------------------------------------------------ saved novel info

/** What the novel page needs to open without internet: title, cover and the chapter list. */
@Serializable
data class SavedNovel(
    val url: String,
    val title: String,
    val cover: String? = null,
    val status: String? = null,
    val synopsis: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterRef> = emptyList(),
    val savedAt: Long = 0,
) {
    fun toDetails() = NovelDetails(
        url = url, title = title, altTitle = null, cover = cover, status = status, info = emptyList(),
        synopsis = synopsis, genres = genres, rating = null, chapters = chapters,
    )
}

object NovelStore {
    private fun fileFor(url: String) = File(AppDirs.novels, sha1(url) + ".json")

    var version by mutableIntStateOf(0)
        private set

    fun save(d: NovelDetails, fallbackCover: String? = null) {
        if (d.chapters.isEmpty()) return
        val novel = SavedNovel(
            url = d.url, title = d.title, cover = d.cover ?: fallbackCover, status = d.status,
            synopsis = d.synopsis, genres = d.genres, chapters = d.chapters, savedAt = System.currentTimeMillis(),
        )
        runCatching { fileFor(d.url).writeAtomically(AppJson.encodeToString(SavedNovel.serializer(), novel)) }
        synchronized(this) { version++ }
    }

    fun load(url: String): SavedNovel? = runCatching {
        AppJson.decodeFromString(SavedNovel.serializer(), fileFor(url).readText())
    }.getOrNull()

    fun all(): List<SavedNovel> = AppDirs.novels.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching { AppJson.decodeFromString(SavedNovel.serializer(), f.readText()) }.getOrNull()
    }
}
