/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ai

import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.lyrics.ClaudeTranslator
import com.convx.desktop.lyrics.LyricsTranslator
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
 * The phone's AiChatClient: a system prompt and one question, sent to the AI provider chosen
 * under AI lyrics translation, for what AI does beyond translating (DJ mixes, playlist edits,
 * track insights). The OpenAI-style providers share one request; Claude has its own.
 */
object AiChat {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** What's missing before a request can be made, in words to show; null when nothing is. */
    fun problem(settings: Settings = AppSettings.value): String? = when {
        settings.aiProvider == "DeepL" -> "DeepL only translates. Choose another AI provider in Settings."
        Secrets[LyricsTranslator.AiKey].isNullOrBlank() -> "Add an AI provider and its API key in Settings first."
        settings.aiProvider != "Claude" && settings.aiBaseUrl.isBlank() -> "Set the provider's URL in Settings first."
        else -> null
    }

    suspend fun complete(system: String, user: String): Result<String> {
        val settings = AppSettings.value
        problem(settings)?.let { return Result.failure(Exception(it)) }
        val key = Secrets[LyricsTranslator.AiKey].orEmpty().trim()
        return if (settings.aiProvider == "Claude") {
            ClaudeTranslator.complete(system, user, key, settings.aiModel)
        } else {
            chatCompletion(system, user, key, settings.aiBaseUrl, settings.aiModel)
        }
    }

    private suspend fun chatCompletion(system: String, user: String, key: String, url: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            // A blocking read doesn't notice the coroutine being cancelled; closing the call does.
            val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) return@withContext Result.failure(Exception(errorMessage(response.code, text)))
                    val content = runCatching {
                        JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    }.getOrNull()
                    if (content.isNullOrBlank()) Result.failure(Exception("The AI sent an empty answer")) else Result.success(content)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(Exception("Network error: ${e.message}"))
            } finally {
                cancel?.dispose()
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            JSONObject(body).let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") }
        }.getOrNull()
        return when {
            code == 401 || code == 403 -> "The provider didn't accept the API key"
            !message.isNullOrBlank() -> message
            else -> "AI request failed: HTTP $code"
        }
    }
}
