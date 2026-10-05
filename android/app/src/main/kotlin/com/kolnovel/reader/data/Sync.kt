package com.kolnovel.reader.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

// Same file format and merge rules as the PC app (src/main/kotlin/.../data/Sync.kt on the PC branch),
// so a phone and a PC pointed at the same cloud folder share one library.

/**
 * One device's copy of the library, as written into the shared sync folder.
 * Every device writes only its own file (library-<deviceId>.json) and reads everyone's.
 */
@Serializable
data class SyncFile(
    val format: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val savedAt: Long,
    val entries: List<LibraryEntry>,
)

object LibraryMerge {
    /** Combines what two devices know about one novel. */
    fun merge(a: LibraryEntry, b: LibraryEntry): LibraryEntry {
        val flags = if (b.flagsChangedAt > a.flagsChangedAt) b else a
        val other = if (flags === a) b else a
        val progress = if (b.lastReadAt > a.lastReadAt) b else a
        return flags.copy(
            title = flags.title.ifBlank { other.title },
            cover = flags.cover ?: other.cover,
            lastChapterUrl = progress.lastChapterUrl,
            lastChapterTitle = progress.lastChapterTitle,
            lastParagraph = progress.lastParagraph,
            lastReadAt = progress.lastReadAt,
            readChapters = a.readChapters + b.readChapters,
            knownChapters = maxOf(a.knownChapters, b.knownChapters),
            newChapters = maxOf(a.newChapters, b.newChapters),
            addedAt = listOf(a.addedAt, b.addedAt).filter { it > 0 }.minOrNull() ?: 0,
        )
    }

    fun merge(local: Map<String, LibraryEntry>, others: List<List<LibraryEntry>>): Map<String, LibraryEntry> {
        val out = local.toMutableMap()
        for (list in others) for (e in list) {
            out[e.url] = out[e.url]?.let { merge(it, e) } ?: e
        }
        return out
    }
}

/**
 * Keeps the library, reading progress and read marks the same on every device that uses the same
 * sync folder. On the phone the folder is picked with Android's folder picker, which keeps access to it.
 */
object LibrarySync {
    private const val DIR_NAME = "KolNovelReader"
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private var loop: Job? = null

    var status by mutableStateOf("")
        private set
    var lastSyncAt by mutableStateOf(0L)
        private set
    /** Other devices seen in the folder, by name. */
    var devices by mutableStateOf<List<String>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set

    val folderUri: Uri? get() = SettingsStore.settings.syncTreeUri.ifBlank { null }?.let(Uri::parse)

    val deviceName: String
        get() = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .distinct().joinToString(" ").ifBlank { "Phone" }

    private val deviceId: String
        get() {
            SettingsStore.settings.deviceId.ifBlank { null }?.let { return it }
            val id = UUID.randomUUID().toString().take(8)
            SettingsStore.update { it.copy(deviceId = id) }
            return id
        }

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    /** Starts syncing: now, a few seconds after each change, and every couple of minutes to pick up other devices. */
    fun start() {
        LibraryStore.onChanged = { schedule() }
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (folderUri != null) syncNow()
                delay(120_000)
            }
        }
    }

    fun setFolder(uri: Uri?) {
        SettingsStore.update { it.copy(syncTreeUri = uri?.toString().orEmpty()) }
        devices = emptyList()
        status = ""
        if (uri != null) scope.launch { syncNow() }
    }

    fun syncInBackground() {
        scope.launch { syncNow() }
    }

    private fun schedule() {
        if (folderUri == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(3_000)
            syncNow()
        }
    }

    private fun syncDir(): DocumentFile? {
        val uri = folderUri ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("لا يمكن فتح المجلد")
        if (!root.canWrite()) error("لا توجد صلاحية للكتابة في هذا المجلد. اختره مرة أخرى.")
        // The PC app keeps its files in a "KolNovelReader" folder inside the cloud folder; accept either level.
        if (root.name == DIR_NAME) return root
        return root.findFile(DIR_NAME)?.takeIf { it.isDirectory } ?: root.createDirectory(DIR_NAME)
    }

    @Synchronized
    fun syncNow(): Boolean {
        running = true
        return try {
            val dir = syncDir() ?: return false
            val mine = "library-$deviceId.json"
            val others = dir.listFiles()
                .filter { f -> f.isFile && f.name?.let { it.startsWith("library-") && it.endsWith(".json") && it != mine } == true }
                .mapNotNull { f -> runCatching { readSyncFile(f.uri) }.getOrNull() }
            val merged = LibraryMerge.merge(LibraryStore.entries, others.map { it.entries })
            LibraryStore.replaceFromSync(merged)
            writeOwn(dir, mine, merged.values.toList())
            devices = others.map { it.deviceName }.distinct()
            lastSyncAt = System.currentTimeMillis()
            status = ""
            true
        } catch (e: Exception) {
            status = "تعذرت المزامنة: ${e.message ?: e}"
            false
        } finally {
            running = false
        }
    }

    private fun readSyncFile(uri: Uri): SyncFile {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("تعذرت قراءة الملف")
        return AppJson.decodeFromString(SyncFile.serializer(), text)
    }

    private fun writeOwn(dir: DocumentFile, name: String, entries: List<LibraryEntry>) {
        val existing = dir.findFile(name)
        val old = existing?.let { runCatching { readSyncFile(it.uri) }.getOrNull() }
        // Only rewrite when something changed, so the cloud drive isn't kept busy.
        if (old != null && old.entries.toSet() == entries.toSet() && old.deviceName == deviceName) return
        val file = existing ?: dir.createFile("application/json", name.removeSuffix(".json"))?.also { created ->
            if (created.name != name) created.renameTo(name)
        } ?: error("تعذر إنشاء ملف المزامنة")
        writeText(file.uri, encode(entries))
    }

    private fun encode(entries: List<LibraryEntry>) = AppJson.encodeToString(
        SyncFile.serializer(),
        SyncFile(deviceId = deviceId, deviceName = deviceName, savedAt = System.currentTimeMillis(), entries = entries),
    )

    private fun writeText(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: error("تعذرت الكتابة")
    }

    /** A single file with the whole library, for moving it by hand (save it anywhere, e.g. Google Drive). */
    fun exportTo(uri: Uri) {
        writeText(uri, encode(LibraryStore.entries.values.toList()))
    }

    /** Merges a file made by "export" (on the phone or the PC) or another device's sync file. Returns how many novels it held. */
    fun importFrom(uri: Uri): Int {
        val other = readSyncFile(uri)
        LibraryStore.replaceFromSync(LibraryMerge.merge(LibraryStore.entries, listOf(other.entries)))
        schedule()
        return other.entries.size
    }
}
