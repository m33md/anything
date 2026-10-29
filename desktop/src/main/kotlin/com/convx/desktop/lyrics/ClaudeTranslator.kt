/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.music.api.fitLineCount
import com.convx.music.api.parseTranslationContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The phone's "Claude" translation provider, made to work. The phone sends Claude the same
 * OpenAI-shaped request as the other providers, at the Messages endpoint with a Bearer header,
 * which Anthropic's API turns away. This speaks the Messages API itself: the key as x-api-key,
 * the instructions as the system prompt, and the reply streamed so lines show as they come.
 */
internal object ClaudeTranslator {
    private const val Endpoint = "https://api.anthropic.com/v1/messages"
    private const val ApiVersion = "2026-10-21"

    /** The models Settings offers, as the phone offers three. */
    val models = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")

    /**
     * Models whose safety checks can decline a request; on these the API is asked to retry a
     * declined one on the model it recommends instead of failing. Haiku has no such retry.
     */
    private val withFallback = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun translate(
        text: String,
        language: String,
        apiKey: String,
        model: String,
        mode: String,
        onPartial: (List<String>) -> Unit,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val count = text.lines().size
        val call = client.newCall(request(text, language, apiKey, model.ifBlank { models.first() }, mode, count))
        // A blocking read doesn't notice the coroutine being cancelled; closing the call does.
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception(errorMessage(response.code, response.body.string())))
                }
                val streamed = read(response.body.charStream().buffered().lineSequence()) { soFar ->
                    LyricsTranslator.partialLines(soFar, count).takeIf { it.isNotEmpty() }?.let(onPartial)
                }
                result(streamed, count)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            cancel?.dispose()
        }
    }

    /** What a Messages stream said: its text, why it stopped, and an error if it sent one. */
    class Streamed(val text: String, val stopReason: String?, val error: String?)

    /** Reads a Messages stream's server-sent events; [onText] gets the text so far after each piece. */
    fun read(lines: Sequence<String>, onText: (String) -> Unit): Streamed {
        val text = StringBuilder()
        var stop: String? = null
        var error: String? = null
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val event = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "content_block_delta" -> {
                    val delta = event.optJSONObject("delta") ?: continue
                    // Thinking arrives in its own deltas; only the answer's text is the translation.
                    if (delta.optString("type") == "text_delta") {
                        text.append(delta.optString("text"))
                        onText(text.toString())
                    }
                }
                "message_delta" -> event.optJSONObject("delta")?.let { delta ->
                    if (!delta.isNull("stop_reason")) stop = delta.optString("stop_reason")
                }
                "error" -> error = event.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "Claude sent an error"
            }
        }
        return Streamed(text.toString(), stop, error)
    }

    fun result(streamed: Streamed, count: Int): Result<List<String>> {
        streamed.error?.let { return Result.failure(Exception(it)) }
        if (streamed.stopReason == "refusal") return Result.failure(Exception("Claude declined to translate these lyrics"))
        val lines = parseTranslationContent(streamed.text)
            ?: return Result.failure(Exception(if (streamed.stopReason == "max_tokens") "The translation was cut off" else "Failed to parse translation"))
        return Result.success(fitLineCount(lines, count))
    }

    private fun request(text: String, language: String, apiKey: String, model: String, mode: String, count: Int): Request {
        val system = "You translate song lyrics. Reply with only a JSON array of $count strings, one for each " +
            "line you are given and in the same order, with no other text. Keep an empty line as \"\"."
        val ask = if (mode == "Transcribed") {
            "Write how each of these $count lines sounds, in $language's own script: spell out the " +
                "pronunciation of the original words, without translating what they mean."
        } else {
            "Translate each of these $count lines into $language. Keep each line's meaning and feel, " +
                "with punctuation that suits $language, and keep the lines as they are split."
        }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 64_000)
            put("stream", true)
            put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "$ask\n\n$text")))
            if (model in withFallback) put("fallbacks", "default")
        }
        return Request.Builder()
            .url(Endpoint)
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", ApiVersion)
            .apply { if (model in withFallback) header("anthropic-beta", "server-side-fallback-2026-07-01") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun errorMessage(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return when {
            code == 401 -> "Claude didn't accept the API key"
            !message.isNullOrBlank() -> message
            else -> "Translation failed (HTTP $code)"
        }
    }
}
