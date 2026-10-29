/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.youtube

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google sign-in for YouTube Music, done in a real browser window.
 *
 * The phone signs in inside Android's WebView and reads the cookies and `ytcfg` values from it.
 * A PC has no WebView, and Google turns away sign-ins from embedded browsers such as CEF, so
 * Convx opens Chrome, Edge or Brave with a fresh, empty profile of its own and watches it over
 * the browser's local debugging port. It only looks at the window's address until you reach
 * music.youtube.com signed in; then it reads that site's cookies and the page's visitor data
 * and channel id, exactly what the phone keeps, closes the window and deletes the profile.
 */
object BrowserSignIn {
    private const val Tag = "BrowserSignIn"
    private const val StartUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    private const val MusicHost = "music.youtube.com"
    private const val TimeoutMs = 10 * 60_000L

    /** Cookies that only exist once a Google account is signed in; guest pages set the others. */
    private val AccountCookies = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
    )

    data class Session(val cookie: String, val visitorData: String?, val dataSyncId: String?)

    class NoBrowserException : Exception("Convx needs Chrome, Edge or Brave to sign in to Google")
    class ClosedException : Exception("The sign-in window was closed before you finished")

    /** The browsers Google is happy to sign in on, in the order Convx prefers them. */
    fun findBrowser(): File? {
        val programFiles = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
        val relative = listOf(
            "Google/Chrome/Application/chrome.exe",
            "Microsoft/Edge/Application/msedge.exe",
            "BraveSoftware/Brave-Browser/Application/brave.exe",
        )
        return relative.firstNotNullOfOrNull { path -> programFiles.map { File(it, path) }.firstOrNull { it.isFile } }
    }

    /** Opens the window and waits until you've signed in, closed it, or ten minutes have passed. */
    suspend fun run(): Session = withContext(Dispatchers.IO) {
        val browser = findBrowser() ?: throw NoBrowserException()
        val profile = File(AppDirs.cache, "sign-in-browser")
        profile.deleteRecursively()
        profile.mkdirs()
        Log.i(Tag, "Opening ${browser.name} for Google sign-in")
        val process = ProcessBuilder(
            browser.path,
            "--user-data-dir=${profile.path}",
            "--remote-debugging-port=0",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
            "--window-size=520,780",
            "--new-window",
            StartUrl,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        var devTools: DevTools? = null
        try {
            val cdp = DevTools(awaitEndpoint(profile, process))
            devTools = cdp
            watch(cdp, process)
        } finally {
            withContext(NonCancellable) {
                devTools?.let { cdp ->
                    withTimeoutOrNull(3_000) { runCatching { cdp.send("Browser.close") } }
                    cdp.close()
                }
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.descendants().forEach { it.destroy() }
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                // Chrome's helpers can hold files for a moment after the browser exits.
                repeat(10) {
                    if (profile.deleteRecursively()) return@withContext
                    delay(500)
                }
                Log.w(Tag, "Couldn't delete the sign-in profile at ${profile.path}")
            }
        }
    }

    /** The browser writes its debugging port and path into the profile once it's listening. */
    private suspend fun awaitEndpoint(profile: File, process: Process): String {
        val file = File(profile, "DevToolsActivePort")
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw ClosedException()
            val lines = runCatching { file.readLines() }.getOrNull()
            if (lines != null && lines.size >= 2 && lines[0].toIntOrNull() != null) {
                return "ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}"
            }
            delay(200)
        }
        throw IOException("The browser didn't open its sign-in window")
    }

    private suspend fun watch(cdp: DevTools, process: Process): Session {
        val deadline = System.currentTimeMillis() + TimeoutMs
        var attached: Pair<String, String>? = null // target id to DevTools session
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            if (!process.isAlive) throw ClosedException()
            val targets = cdp.send("Target.getTargets")["targetInfos"].asObjects()
            val page = targets.firstOrNull { it.text("type") == "page" && it.text("url")?.startsWith("https://$MusicHost") == true }
                ?: continue
            val cookies = cdp.send("Storage.getCookies")["cookies"].asObjects().filter(::sentToMusic)
            if (cookies.none { it.text("name") in AccountCookies }) continue
            val cookie = cookies.joinToString("; ") { "${it.text("name")}=${it.text("value")}" }

            // Only now, on YouTube Music and signed in, does Convx look inside the page.
            val targetId = page.text("targetId") ?: continue
            var session = attached?.takeIf { it.first == targetId }?.second
            if (session == null) {
                val params = buildJsonObject { put("targetId", targetId); put("flatten", true) }
                session = cdp.send("Target.attachToTarget", params)["sessionId"]?.jsonPrimitive?.contentOrNull ?: continue
                attached = targetId to session
            }
            val config = readConfig(cdp, session) ?: continue
            Log.i(Tag, "Signed in; ${cookies.size} cookies, data sync id ${if (config.second != null) "found" else "missing"}")
            return Session(cookie, config.first, config.second)
        }
        throw IOException("Sign-in timed out after ten minutes. Try again.")
    }

    /** The page's `ytcfg` VISITOR_DATA and DATASYNC_ID, as the phone's JavaScript bridge reads them. */
    private suspend fun readConfig(cdp: DevTools, session: String): Pair<String, String?>? {
        val expression = "JSON.stringify((window.yt && window.yt.config_) ? " +
            "[window.yt.config_.VISITOR_DATA || null, window.yt.config_.DATASYNC_ID || null] : [null, null])"
        repeat(10) {
            val result = runCatching {
                cdp.send(
                    "Runtime.evaluate",
                    buildJsonObject { put("expression", expression); put("returnByValue", true) },
                    session,
                )
            }.getOrNull()
            val value = result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            val pair = value?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
            val visitor = pair?.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (visitor != null) {
                val dataSync = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                // Keep DATASYNC_ID's "||" suffix: it tells channels on one account apart.
                return percentDecode(visitor) to dataSync?.let { percentDecode(it) }
            }
            delay(500)
        }
        return null
    }

    /** What the browser would send with a request to https://music.youtube.com/. */
    private fun sentToMusic(cookie: JsonObject): Boolean {
        val domain = cookie.text("domain") ?: return false
        val path = cookie.text("path") ?: "/"
        val domainMatches = if (domain.startsWith(".")) MusicHost == domain.drop(1) || MusicHost.endsWith(domain) else MusicHost == domain
        return domainMatches && (path == "/" || path.isEmpty())
    }

    /** Android's Uri.decode: %XX only, a "+" stays a "+". */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 <= text.lastIndex) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.asObjects(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** A minimal Chrome DevTools Protocol client: numbered commands over one WebSocket. */
    private class DevTools(url: String) : AutoCloseable {
        private val client = Http.client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val ids = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val opened = CompletableDeferred<Unit>()

        private val socket: WebSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
                    pending.remove(id)?.complete(message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(IOException("The browser closed the connection"))
            },
        )

        private fun fail(error: Throwable) {
            opened.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }

        suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()), sessionId: String? = null): JsonObject {
            withTimeout(10_000) { opened.await() }
            val id = ids.getAndIncrement()
            val reply = CompletableDeferred<JsonObject>()
            pending[id] = reply
            val message = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
                if (sessionId != null) put("sessionId", sessionId)
            }
            if (!socket.send(message.toString())) {
                pending.remove(id)
                throw IOException("The browser closed the connection")
            }
            val answer = try {
                withTimeout(15_000) { reply.await() }
            } finally {
                pending.remove(id)
            }
            answer["error"]?.jsonObject?.let { error ->
                throw IOException(error["message"]?.jsonPrimitive?.contentOrNull ?: "DevTools refused $method")
            }
            return answer["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override fun close() {
            socket.close(1000, null)
        }
    }
}
