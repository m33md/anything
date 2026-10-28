/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * A value persisted as one JSON file. Writes are debounced and atomic (temp file + move),
 * so a crash mid-write leaves the previous copy intact.
 */
@OptIn(FlowPreview::class)
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: () -> T,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _state = MutableStateFlow(read() ?: default())
    val state: StateFlow<T> = _state
    val value: T get() = _state.value

    init {
        _state.drop(1).debounce(400).onEach { write(it) }.launchIn(scope)
    }

    fun update(transform: (T) -> T) = _state.update(transform)

    /** Writes the current value now; called on exit so the last debounce window isn't lost. */
    fun flush() = write(_state.value)

    private fun read(): T? = runCatching {
        if (!file.isFile) return null
        StoreJson.decodeFromString(serializer, file.readText())
    }.onFailure {
        Log.e("Store", "Could not read ${file.name}; keeping a backup and starting fresh", it)
        runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
    }.getOrNull()

    private fun write(value: T) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(StoreJson.encodeToString(serializer, value))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.e("Store", "Could not save ${file.name}", it) }
    }
}
