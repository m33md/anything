/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.convx.music.api.DeepLService
import com.convx.music.api.MistralService
import com.convx.music.api.OpenRouterService
import com.convx.music.api.OpenRouterStreamingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The phone's AI lyrics translation (its LyricsTranslationHelper): the lyrics' non-empty lines
 * sent to the provider chosen in Settings, the lines shown under the lyrics as they stream in,
 * and the result kept with the song for the language and mode it was made in.
 */
object LyricsTranslator {
    private const val Tag = "Translate"

    /** Where the keys live in [Secrets]: one for the AI providers, one for DeepL, as on the phone. */
    const val AiKey = "ai.key"
    const val DeepLKey = "deepl.key"

    sealed interface Status {
        data object Idle : Status
        data object Translating : Status
        data object Success : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Lines of the translation under way, by song, as far as they've arrived. */
    class Partial(val songId: String, val key: Int, val lines: List<String>)

    private val _partial = MutableStateFlow<Partial?>(null)
    val partial: StateFlow<Partial?> = _partial.asStateFlow()

    private var job: Job? = null

    /** Identifies [lyrics]' text, so a translation is only shown on the lines it was made from. */
    fun key(lyrics: Lyrics): Int = lyrics.lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }.hashCode()

    fun keyFor(settings: Settings): String = if (settings.aiProvider == "DeepL") DeepLKey else AiKey

    fun hasKey(settings: Settings = AppSettings.value): Boolean = !Secrets[keyFor(settings)].isNullOrBlank()

    /** The kept translation of [lyrics] for [songId] in the chosen language and mode, if there is one. */
    fun saved(songId: String, lyrics: Lyrics, settings: Settings): SavedTranslation? =
        LyricsTweaks.of(songId).translation?.takeIf {
            it.language == settings.translateLanguage && it.mode == settings.translateMode && it.key == key(lyrics)
        }

    /** [translated] (one per non-empty line) set against every line of [lyrics]. */
    fun align(lyrics: Lyrics, translated: List<String>): List<String?> {
        var next = 0
        return lyrics.lines.map { line ->
            if (line.text.isBlank()) null else translated.getOrNull(next++)?.takeIf { it.isNotBlank() }
        }
    }

    /** The phone's switch turned on: translates [lyrics] with the provider and language in Settings. */
    fun translate(song: Song, lyrics: Lyrics) {
        job?.cancel()
        val settings = AppSettings.value
        val key = key(lyrics)
        _status.value = Status.Translating
        _partial.value = Partial(song.id, key, emptyList())
        job = AppScope.launch(Dispatchers.IO) {
            try {
                val result = request(lyrics, settings) { lines -> _partial.value = Partial(song.id, key, lines) }
                result.onSuccess { lines ->
                    val count = lyrics.lines.count { it.text.isNotBlank() }
                    LyricsTweaks.update(song.id) {
                        it.copy(translation = SavedTranslation(lines.take(count), settings.translateLanguage, settings.translateMode, key))
                    }
                    _partial.value = null
                    _status.value = Status.Success
                    delay(3_000)
                    if (_status.value == Status.Success) _status.value = Status.Idle
                }.onFailure {
                    Log.w(Tag, "Translation failed", it)
                    _partial.value = null
                    _status.value = Status.Error(it.message ?: "Translation failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(Tag, "Translation failed", e)
                _partial.value = null
                _status.value = Status.Error(e.message ?: "Translation failed")
            }
        }
    }

    /** The phone's switch turned off: drops [songId]'s translation. */
    fun clear(songId: String) {
        if (_partial.value?.songId == songId) {
            job?.cancel()
            _partial.value = null
            _status.value = Status.Idle
        }
        LyricsTweaks.update(songId) { it.copy(translation = null) }
    }

    /** Clears an error once it has been read, as the phone's chip goes when tapped. */
    fun dismiss() {
        if (_status.value is Status.Error) _status.value = Status.Idle
    }

    private suspend fun request(lyrics: Lyrics, settings: Settings, onPartial: (List<String>) -> Unit): Result<List<String>> {
        val apiKey = Secrets[keyFor(settings)].orEmpty()
        if (apiKey.isBlank()) return Result.failure(Exception("API key is required"))
        val lines = lyrics.lines.map { it.text }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result.failure(Exception("No lyrics to translate"))
        val language = settings.translateLanguage
        if (language.isBlank()) return Result.failure(Exception("Target language is required"))
        val text = lines.joinToString("\n")
        val name = TranslationLanguages[language]
            ?: Locale.forLanguageTag(language).displayLanguage.takeIf { it.isNotBlank() && it != language }
            ?: language
        val mode = settings.translateMode
        val model = settings.aiModel
        return when (settings.aiProvider) {
            "DeepL" -> DeepLService.translate(text, language, apiKey, settings.deeplFormality)
            "Mistral" -> MistralService.translate(text, name, apiKey, model, mode)
            "Custom" -> OpenRouterService.translate(text, name, apiKey, settings.aiBaseUrl, model, mode)
            "Claude" -> ClaudeTranslator.translate(text, name, apiKey, model, mode, onPartial)
            else -> stream(text, name, apiKey, settings.aiBaseUrl, model, mode, lines.size, onPartial)
        }
    }

    private suspend fun stream(
        text: String,
        language: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        count: Int,
        onPartial: (List<String>) -> Unit,
    ): Result<List<String>> {
        var lines: List<String>? = null
        var error: String? = null
        val content = StringBuilder()
        OpenRouterStreamingService.streamTranslation(text, language, apiKey, baseUrl, model, mode).collect { chunk ->
            when (chunk) {
                is OpenRouterStreamingService.StreamChunk.Content -> {
                    content.append(chunk.text)
                    partialLines(content.toString(), count).takeIf { it.isNotEmpty() }?.let(onPartial)
                }
                is OpenRouterStreamingService.StreamChunk.Complete -> lines = chunk.translatedLines
                is OpenRouterStreamingService.StreamChunk.Error -> error = chunk.message
            }
        }
        return when {
            error != null -> Result.failure(Exception(error))
            lines != null -> Result.success(lines!!)
            else -> Result.failure(Exception("No translation received"))
        }
    }

    /** The whole strings in a JSON array still arriving, as the phone reads a stream. */
    internal fun partialLines(content: String, max: Int): List<String> {
        val start = content.indexOf('[')
        if (start == -1) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        var escaped = false
        var index = start + 1
        while (index < content.length && lines.size < max) {
            val char = content[index]
            when {
                // Unescaped as they arrive, so a quote in a line doesn't show its backslash.
                escaped -> {
                    current.append(if (char == 'n' || char == 't') ' ' else char)
                    escaped = false
                }
                char == '\\' && inside -> escaped = true
                char == '"' -> {
                    if (inside) {
                        lines += current.toString()
                        current.clear()
                    }
                    inside = !inside
                }
                inside -> current.append(char)
                char == ']' -> break
            }
            index++
        }
        return lines
    }
}
