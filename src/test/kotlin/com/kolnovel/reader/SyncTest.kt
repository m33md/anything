package com.kolnovel.reader

import com.kolnovel.reader.data.AppJson
import com.kolnovel.reader.data.LibraryEntry
import com.kolnovel.reader.data.LibraryMerge
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.LibrarySync
import com.kolnovel.reader.data.SettingsStore
import com.kolnovel.reader.data.SyncFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTest {
    private val url = "https://kolnovel.com/series/test-sync/"

    @Test
    fun mergeKeepsNewestChoiceAndFurthestProgress() {
        val pc = LibraryEntry(
            url, "رواية", inLibrary = true, favorite = true, flagsChangedAt = 100,
            lastChapterUrl = "c5", lastReadAt = 500, readChapters = setOf("c1", "c2"), addedAt = 50,
        )
        // The phone removed it from favourites later, but read less recently.
        val phone = pc.copy(favorite = false, flagsChangedAt = 200, lastChapterUrl = "c3", lastReadAt = 300, readChapters = setOf("c3"), addedAt = 80)
        val m = LibraryMerge.merge(pc, phone)
        assertFalse(m.favorite)
        assertTrue(m.inLibrary)
        assertEquals("c5", m.lastChapterUrl)
        assertEquals(setOf("c1", "c2", "c3"), m.readChapters)
        assertEquals(50, m.addedAt)
        assertEquals(m, LibraryMerge.merge(phone, pc))
    }

    @Test
    fun folderSyncPicksUpOtherDevicesAndWritesOwnFile() {
        System.setProperty("user.home", File("build/home-sync").absolutePath)
        val shared = File("build/sync-folder").apply { deleteRecursively(); mkdirs() }
        val dir = File(shared, "KolNovelReader").apply { mkdirs() }
        val other = LibraryEntry(url, "رواية من الهاتف", inLibrary = true, flagsChangedAt = System.currentTimeMillis(), lastChapterUrl = "c9", lastReadAt = 900)
        File(dir, "library-phone123.json").writeText(
            AppJson.encodeToString(SyncFile.serializer(), SyncFile(deviceId = "phone123", deviceName = "Phone", savedAt = 1, entries = listOf(other)))
        )
        SettingsStore.update { it.copy(syncFolder = shared.absolutePath) }
        assertTrue(LibrarySync.syncNow())
        assertEquals("c9", LibraryStore[url]?.lastChapterUrl)
        assertEquals(listOf("Phone"), LibrarySync.devices)
        val mine = File(dir, "library-${SettingsStore.settings.deviceId}.json")
        assertTrue(mine.exists())
        assertTrue(AppJson.decodeFromString(SyncFile.serializer(), mine.readText()).entries.any { it.url == url })
        SettingsStore.update { it.copy(syncFolder = "") }
    }
}
