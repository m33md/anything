/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small credentials (Last.fm's session, a ListenBrainz token), kept out of settings.json and,
 * on Windows, encrypted for this user account the way browsers keep their cookies.
 */
object Secrets {
    private const val Tag = "Secrets"
    private val windows = System.getProperty("os.name").lowercase().contains("win")
    private val file by lazy { File(AppDirs.data, "secrets.dat") }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    private var loaded = false

    val values: StateFlow<Map<String, String>> get() {
        ensureLoaded()
        return _values.asStateFlow()
    }

    operator fun get(key: String): String? {
        ensureLoaded()
        return _values.value[key]
    }

    /** Stores [value] under [key], or forgets it when [value] is null or blank. */
    @Synchronized
    operator fun set(key: String, value: String?) {
        ensureLoaded()
        val next = if (value.isNullOrBlank()) _values.value - key else _values.value + (key to value)
        if (next == _values.value) return
        _values.value = next
        runCatching { write(next) }.onFailure { Log.e(Tag, "Couldn't save credentials", it) }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        _values.value = runCatching {
            val bytes = file.readBytes()
            val plain = if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes
            Json.decodeFromString(serializer, String(plain, Charsets.UTF_8))
        }.onFailure { Log.w(Tag, "Couldn't read saved credentials", it) }.getOrDefault(emptyMap())
    }

    private fun write(values: Map<String, String>) {
        val plain = Json.encodeToString(serializer, values).toByteArray(Charsets.UTF_8)
        val bytes = if (windows) Crypt32Util.cryptProtectData(plain) else plain
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.parentFile.mkdirs()
        temp.writeBytes(bytes)
        if (!windows) {
            temp.setReadable(false, false)
            temp.setReadable(true, true)
        }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
