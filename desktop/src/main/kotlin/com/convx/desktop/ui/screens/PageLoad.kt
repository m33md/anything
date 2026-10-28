/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.convx.desktop.core.Log
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recently opened pages, so Back shows them instantly instead of refetching. */
object PageCache {
    private val pages = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > 48
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = synchronized(pages) { pages[key] as T? }

    fun put(key: String, value: Any) = synchronized(pages) { pages[key] = value }

    fun remove(key: String) = synchronized(pages) { pages.remove(key) }

    fun clear() = synchronized(pages) { pages.clear() }
}

@Stable
class PageLoad<T : Any>(val key: String, initial: T?) {
    var value by mutableStateOf(initial)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(initial == null)
    internal var attempt by mutableIntStateOf(0)

    fun retry() {
        PageCache.remove(key)
        attempt++
    }

    /** For pages that grow (continuations): replace the value and keep the cache in step. */
    fun update(transform: (T) -> T) {
        val current = value ?: return
        val next = transform(current)
        value = next
        PageCache.put(key, next)
    }
}

/** Loads a page once per [key], off the UI thread, with a cache shared across visits. */
@Composable
fun <T : Any> rememberPage(key: String, load: suspend () -> T): PageLoad<T> {
    val page = remember(key) { PageLoad(key, PageCache.get<T>(key)) }
    LaunchedEffect(page, page.attempt) {
        if (page.value != null && page.attempt == 0) return@LaunchedEffect
        page.loading = true
        page.error = null
        try {
            val result = withContext(Dispatchers.IO) { load() }
            page.value = result
            PageCache.put(key, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("Page", "Couldn't load $key", e)
            page.error = e
        }
        page.loading = false
    }
    return page
}

/** Spinner, error with retry, or the page itself. */
@Composable
fun <T : Any> PageContent(page: PageLoad<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val value = page.value
    when {
        value != null -> content(value)
        page.error != null -> MessageState(
            title = "Couldn't load this page",
            message = friendlyError(page.error),
            icon = "error",
            actionText = "Try again",
            onAction = { page.retry() },
            modifier = modifier,
        )
        else -> LoadingState(modifier)
    }
}

fun friendlyError(error: Throwable?): String {
    val text = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
    return when {
        error is java.net.UnknownHostException || "UnknownHost" in text -> "You're offline, or YouTube Music can't be reached."
        error is java.net.SocketTimeoutException || "timeout" in text.lowercase() -> "YouTube Music took too long to answer."
        text.isBlank() -> "Something went wrong."
        else -> text.take(200)
    }
}
