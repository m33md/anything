/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.convx.desktop.lyrics.ClaudeTranslator
import com.convx.desktop.lyrics.LyricsTranslator
import com.convx.desktop.lyrics.TranslationLanguages
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton

/** A translation provider as the phone lists it: its endpoint, models, and where its keys come from. */
private class AiProvider(val name: String, val url: String, val models: List<String>, val help: String = "", val keys: String? = null)

private val AiProviders = listOf(
    AiProvider(
        "OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
        listOf(
            "google/gemini-3.1-flash-lite", "google/gemini-3.5-flash", "x-ai/grok-4.3",
            "deepseek/deepseek-v3.1-terminus:exacto", "openai/gpt-4o-mini", "google/gemini-2.5-flash-lite",
        ),
        "Visit openrouter.ai for free and paid models", "https://openrouter.ai/keys",
    ),
    AiProvider(
        "Groq", "https://api.groq.com/openai/v1/chat/completions",
        listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
        "Visit console.groq.com/keys — free tier, no card required", "https://console.groq.com/keys",
    ),
    AiProvider(
        "OpenAI", "https://api.openai.com/v1/chat/completions",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo"),
        "Visit platform.openai.com/api-keys", "https://platform.openai.com/api-keys",
    ),
    AiProvider(
        "Perplexity", "https://api.perplexity.ai/chat/completions",
        listOf("sonar", "sonar-pro", "sonar-reasoning"),
        "Visit perplexity.ai/settings/api", "https://www.perplexity.ai/settings/api",
    ),
    // The phone lists Claude 3.5 models at this endpoint with an OpenAI-shaped request; here
    // ClaudeTranslator speaks the endpoint's own API, with the current models.
    AiProvider(
        "Claude", "https://api.anthropic.com/v1/messages", ClaudeTranslator.models,
        "Visit console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/keys",
    ),
    AiProvider(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        listOf(
            "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-flash",
            "gemini-3.5-flash", "gemini-3-flash", "gemini-3.1-flash-lite", "gemini-3.1-pro",
        ),
        "Visit aistudio.google.com/apikey", "https://aistudio.google.com/apikey",
    ),
    AiProvider("XAi", "https://api.x.ai/v1/chat/completions", listOf("grok-4-1-fast", "grok-vision-beta"), "Visit console.x.ai", "https://console.x.ai"),
    AiProvider(
        "Mistral", "https://api.mistral.ai/v1/chat/completions",
        listOf("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest", "mistral-tiny-latest"),
        "Visit console.mistral.ai/api-keys", "https://console.mistral.ai/api-keys",
    ),
    AiProvider("DeepL", "https://api.deepl.com/v2/translate", emptyList(), "Visit deepl.com/pro-api for free and paid keys", "https://www.deepl.com/pro-api"),
    AiProvider("Custom", "", emptyList()),
)

private const val CustomModel = "\u0000custom"

/**
 * The phone's AI Lyrics Translation page: which provider, its key and model, and what lyrics are
 * translated into. The translating itself is switched on per song from the lyrics menu.
 */
@Composable
fun AiTranslationSettings() {
    val settings by AppSettings.state.collectAsState()
    val secrets by Secrets.values.collectAsState()
    val provider = AiProviders.firstOrNull { it.name == settings.aiProvider } ?: AiProviders.first()
    val deepL = provider.name == "DeepL"

    SettingsSection("Provider", footer = provider.help.ifBlank { null }) {
        ChoiceRow("Provider", options = AiProviders.map { it.name to it.name }, selected = provider.name, icon = "explore_outlined", tint = SystemColors.Indigo) { name ->
            val chosen = AiProviders.first { it.name == name }
            // As on the phone: a new provider brings its own endpoint and first model.
            AppSettings.update {
                it.copy(
                    aiProvider = name,
                    aiBaseUrl = if (name == "Custom" || name == "DeepL") "" else chosen.url,
                    aiModel = chosen.models.firstOrNull().orEmpty(),
                )
            }
        }
        provider.keys?.let { url ->
            RowDivider()
            LinkRow("Get an API key", icon = "key", tint = SystemColors.Indigo) { openUrl(url) }
        }
        if (provider.name == "Custom") {
            RowDivider()
            LinkRow("Base URL", settings.aiBaseUrl.ifBlank { "Not set" }, icon = "link", tint = SystemColors.Indigo) {
                editText("Base URL", settings.aiBaseUrl) { url -> AppSettings.update { it.copy(aiBaseUrl = url) } }
            }
        }
    }
    SectionGap()
    SettingsSection("API credentials", footer = "Kept on this PC only, encrypted with your Windows account.") {
        val keyName = LyricsTranslator.keyFor(settings)
        val key = secrets[keyName].orEmpty()
        LinkRow(
            if (deepL) "DeepL API key" else "API key",
            icon = "key",
            tint = SystemColors.Gray,
            value = if (key.isNotEmpty()) "•".repeat(minOf(key.length, 8)) else "Not set",
        ) {
            editKey(if (deepL) "DeepL API key" else "${provider.name} API key", keyName)
        }
        if (deepL) {
            RowDivider()
            ChoiceRow(
                "Formality",
                options = listOf("default" to "Default", "more" to "More formal", "less" to "Less formal"),
                selected = settings.deeplFormality,
                icon = "tune",
            ) { value -> AppSettings.update { it.copy(deeplFormality = value) } }
        } else if (provider.name != "Custom") {
            RowDivider()
            val models = provider.models
            ChoiceRow(
                "Model",
                options = models.map { it to it } + (CustomModel to if (settings.aiModel in models || settings.aiModel.isBlank()) "Custom…" else settings.aiModel),
                selected = if (settings.aiModel in models) settings.aiModel else CustomModel,
                icon = "tune",
            ) { value ->
                if (value == CustomModel) {
                    editText("Model", settings.aiModel) { model -> AppSettings.update { it.copy(aiModel = model) } }
                } else {
                    AppSettings.update { it.copy(aiModel = value) }
                }
            }
        } else {
            RowDivider()
            LinkRow("Model", settings.aiModel.ifBlank { "Not set" }, icon = "tune") {
                editText("Model", settings.aiModel) { model -> AppSettings.update { it.copy(aiModel = model) } }
            }
        }
    }
    SectionGap()
    SettingsSection(
        "Translation",
        footer = if (deepL) null else "Translation turns the meaning into the language you pick. Transcription writes how the words sound in that language's script.",
    ) {
        if (!deepL) {
            ChoiceRow(
                "Translation mode",
                options = listOf("Literal" to "Translation", "Transcribed" to "Transcription"),
                selected = settings.translateMode,
                icon = "translate",
                tint = SystemColors.Teal,
            ) { value -> AppSettings.update { it.copy(translateMode = value) } }
            RowDivider()
        }
        ChoiceRow(
            "Target language",
            options = TranslationLanguages.entries.sortedBy { it.value }.map { it.key to it.value },
            selected = settings.translateLanguage,
            icon = "language",
            tint = SystemColors.Teal,
        ) { value -> AppSettings.update { it.copy(translateLanguage = value) } }
    }
    SectionGap()
    InfoNote("To translate a song, open the lyrics menu in the player and turn on AI lyrics translation. Each song keeps its translation, so it's only paid for once per language.")
}

private fun editKey(title: String, keyName: String) {
    Overlays.showDialog {
        var key by remember { mutableStateOf("") }
        val saved = !Secrets[keyName].isNullOrBlank()
        DialogCard(title) {
            DialogField("API key", key, secret = true) { key = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                if (saved) {
                    PillButton("Remove key", {
                        Secrets[keyName] = null
                        Overlays.dismissDialog()
                        Overlays.toast("Key removed")
                    })
                }
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (key.isNotBlank()) {
                        Secrets[keyName] = key.trim()
                        Overlays.dismissDialog()
                        Overlays.toast("Key saved")
                    }
                }, filled = key.isNotBlank())
            }
        }
    }
}

private fun editText(title: String, initial: String, onSave: (String) -> Unit) {
    Overlays.showDialog {
        var text by remember { mutableStateOf(initial) }
        DialogCard(title) {
            DialogField(title, text) { text = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    onSave(text.trim())
                    Overlays.dismissDialog()
                }, filled = true)
            }
        }
    }
}
