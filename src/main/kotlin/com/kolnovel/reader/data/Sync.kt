package com.kolnovel.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * One device's copy of the library, as written into the shared sync folder.
 * Every device writes only its own file (library-<deviceId>.json) and reads everyone's,
 * so cloud drives never see two devices writing the same file.
 * The phone app can use the same folder and format.
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
            lastChapterIndex = progress.lastChapterIndex,
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

/** Keeps the library, reading progress and read marks the same on every device that uses the same sync folder. */
object LibrarySync {
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

    val folder: File? get() = SettingsStore.settings.syncFolder.ifBlank { null }?.let { File(it, "KolNovelReader") }

    val deviceName: String
        get() = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC"

    private val deviceId: String
        get() {
            SettingsStore.settings.deviceId.ifBlank { null }?.let { return it }
            val id = UUID.randomUUID().toString().take(8)
            SettingsStore.update { it.copy(deviceId = id) }
            return id
        }

    /** Starts syncing: now, a few seconds after each change, and every couple of minutes to pick up other devices. */
    fun start() {
        LibraryStore.onChanged = { schedule() }
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (folder != null) syncNow()
                delay(120_000)
            }
        }
    }

    fun setFolder(path: String) {
        SettingsStore.update { it.copy(syncFolder = path) }
        devices = emptyList()
        status = ""
        if (path.isNotBlank()) scope.launch { syncNow() }
    }

    private fun schedule() {
        if (folder == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(3_000)
            syncNow()
        }
    }

    @Synchronized
    fun syncNow(): Boolean {
        val dir = folder ?: return false
        return try {
            dir.mkdirs()
            val mine = "library-$deviceId.json"
            val others = dir.listFiles { f -> f.name.startsWith("library-") && f.name.endsWith(".json") && f.name != mine }
                .orEmpty()
                .mapNotNull { f -> runCatching { AppJson.decodeFromString(SyncFile.serializer(), f.readText()) }.getOrNull() }
            val merged = LibraryMerge.merge(LibraryStore.entries, others.map { it.entries })
            LibraryStore.replaceFromSync(merged)
            writeOwn(dir, merged.values.toList())
            devices = others.map { it.deviceName }.distinct()
            lastSyncAt = System.currentTimeMillis()
            status = ""
            true
        } catch (e: Exception) {
            status = "تعذرت المزامنة: ${e.message ?: e}"
            false
        }
    }

    private fun writeOwn(dir: File, entries: List<LibraryEntry>) {
        val file = File(dir, "library-$deviceId.json")
        val old = runCatching { AppJson.decodeFromString(SyncFile.serializer(), file.readText()) }.getOrNull()
        // Only rewrite when something changed, so the cloud drive isn't kept busy.
        if (old != null && old.entries.toSet() == entries.toSet() && old.deviceName == deviceName) return
        val text = AppJson.encodeToString(SyncFile.serializer(), SyncFile(deviceId = deviceId, deviceName = deviceName, savedAt = System.currentTimeMillis(), entries = entries))
        file.writeAtomically(text)
    }

    /** A single file with the whole library, for moving it by hand. */
    fun exportTo(file: File) {
        val text = AppJson.encodeToString(
            SyncFile.serializer(),
            SyncFile(deviceId = deviceId, deviceName = deviceName, savedAt = System.currentTimeMillis(), entries = LibraryStore.entries.values.toList()),
        )
        file.writeText(text)
    }

    /** Merges a file made by [exportTo] (or another device's sync file) into this library. Returns how many novels it held. */
    fun importFrom(file: File): Int {
        val other = AppJson.decodeFromString(SyncFile.serializer(), file.readText())
        LibraryStore.replaceFromSync(LibraryMerge.merge(LibraryStore.entries, listOf(other.entries)))
        schedule()
        return other.entries.size
    }

    /** Cloud folders found on this PC, as (label, path). */
    fun suggestedFolders(): List<Pair<String, String>> {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("OneDrive")?.let { "OneDrive" to it },
            System.getenv("OneDriveConsumer")?.let { "OneDrive" to it },
            "Google Drive" to "G:\\My Drive",
            "Google Drive" to "G:\\محرك أقراصي",
            "Google Drive" to "$home\\Google Drive",
            "Google Drive" to "$home\\My Drive",
            "Dropbox" to "$home\\Dropbox",
        )
        return candidates.filter { File(it.second).isDirectory }.distinctBy { File(it.second).absolutePath.lowercase() }
    }
}
