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
 * A PC has no WebView, and Google turns away sign-ins from embedded browsers such as CEF, and
 * from a browser something is driving over its debugging port. So it happens in two steps.
 * First Convx opens Chrome, Edge or Brave with a fresh, empty profile of its own and nothing
 * else: an ordinary window, which Google accepts. You sign in, and close the window once YouTube
 * Music shows your account (or press Done in Convx, which closes it for you). Then Convx opens
 * that profile once more, out of sight, reads music.youtube.com's cookies and the page's visitor
 * data and channel id (exactly what the phone keeps), closes it and deletes the profile.
 */
object BrowserSignIn {
    private const val Tag = "BrowserSignIn"
    private const val StartUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    private const val MusicHost = "music.youtube.com"
    private const val TimeoutMs = 15 * 60_000L

    /** Cookies that only exist once a Google account is signed in; guest pages set the others. */
    private val AccountCookies = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
    )

    data class Session(val cookie: String, val visitorData: String?, val dataSyncId: String?)

    class NoBrowserException : Exception("Convx needs Chrome, Edge or Brave to sign in to Google")
    class ClosedException : Exception("The sign-in window was closed before you signed in")

    /** The sign-in window while it's open, for [finish]. */
    @Volatile private var window: Process? = null

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

    /**
     * Opens the window, waits for you to sign in and close it (up to fifteen minutes), then reads
     * the account from the profile it leaves.
     */
    suspend fun run(): Session = withContext(Dispatchers.IO) {
        val browser = findBrowser() ?: throw NoBrowserException()
        val profile = File(AppDirs.cache, "sign-in-browser")
        profile.deleteRecursively()
        profile.mkdirs()
        try {
            Log.i(Tag, "Opening ${browser.name} for Google sign-in")
            val shown = openBrowser(browser, profile, "--window-size=520,780", "--new-window", StartUrl)
            window = shown
            try {
                awaitClosed(shown)
            } finally {
                window = null
                stop(shown)
            }
            readAccount(browser, profile)
        } finally {
            withContext(NonCancellable) {
                // Chrome's helpers can hold files for a moment after the browser exits.
                repeat(10) {
                    if (profile.deleteRecursively()) return@withContext
                    delay(500)
                }
                Log.w(Tag, "Couldn't delete the sign-in profile at ${profile.path}")
            }
        }
    }

    /**
     * Closes the sign-in window the way its close button does, so the browser saves its cookies
     * on the way out; [run] then reads them.
     */
    fun finish() {
        window?.let { closeWindows(it.pid()) }
    }

    /** Asks every window of the browser process [pid] to close, as its close button does. */
    private fun closeWindows(pid: Long) {
        runCatching {
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            user32.EnumWindows({ hwnd, _ ->
                val owner = com.sun.jna.ptr.IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value.toLong() == pid && user32.IsWindowVisible(hwnd)) {
                    user32.PostMessage(hwnd, com.sun.jna.platform.win32.WinUser.WM_CLOSE, null, null)
                }
                true
            }, null)
        }.onFailure { Log.w(Tag, "Couldn't close the sign-in window: ${it.message}") }
    }

    private fun openBrowser(browser: File, profile: File, vararg extra: String): Process =
        ProcessBuilder(
            listOf(
                browser.path,
                "--user-data-dir=${profile.path}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-sync",
            ) + extra,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

    /** Waits for the window to close; nothing looks inside it meanwhile. */
    private suspend fun awaitClosed(process: Process) {
        val deadline = System.currentTimeMillis() + TimeoutMs
        while (process.isAlive) {
            if (System.currentTimeMillis() > deadline) throw IOException("Sign-in timed out after fifteen minutes. Try again.")
            delay(400)
        }
    }

    private fun stop(process: Process) {
        if (!process.isAlive) return
        closeWindows(process.pid())
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroy() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /**
     * The profile again, headless, now that sign-in is over: YouTube Music's page for its
     * `ytcfg` values, and the cookies the browser kept for it.
     */
    private suspend fun readAccount(browser: File, profile: File): Session {
        File(profile, "DevToolsActivePort").delete()
        val process = openBrowser(browser, profile, "--headless=new", "--remote-debugging-port=0", "--mute-audio", "about:blank")
        var devTools: DevTools? = null
        try {
            val cdp = DevTools(awaitEndpoint(profile, process))
            devTools = cdp
            // Headless says so in its user agent; YouTube Music gets the browser's ordinary one.
            val agent = cdp.send("Browser.getVersion")["userAgent"]?.jsonPrimitive?.contentOrNull?.replace("HeadlessChrome", "Chrome")
            val targetId = cdp.send("Target.createTarget", buildJsonObject { put("url", "about:blank") })["targetId"]
                ?.jsonPrimitive?.contentOrNull ?: throw IOException("The browser didn't open YouTube Music")
            val session = cdp.send("Target.attachToTarget", buildJsonObject { put("targetId", targetId); put("flatten", true) })["sessionId"]
                ?.jsonPrimitive?.contentOrNull ?: throw IOException("The browser didn't open YouTube Music")
            if (agent != null) cdp.send("Emulation.setUserAgentOverride", buildJsonObject { put("userAgent", agent) }, session)
            cdp.send("Page.navigate", buildJsonObject { put("url", "https://$MusicHost/") }, session)
            val config = readConfig(cdp, session)
            val cookies = cdp.send("Storage.getCookies")["cookies"].asObjects().filter(::sentToMusic)
            if (cookies.none { it.text("name") in AccountCookies }) throw ClosedException()
            val cookie = cookies.joinToString("; ") { "${it.text("name")}=${it.text("value")}" }
            Log.i(Tag, "Signed in; ${cookies.size} cookies, visitor data ${if (config != null) "found" else "missing"}, data sync id ${if (config?.second != null) "found" else "missing"}")
            return Session(cookie, config?.first, config?.second)
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
            }
        }
    }

    /** The browser writes its debugging port and path into the profile once it's listening. */
    private suspend fun awaitEndpoint(profile: File, process: Process): String {
        val file = File(profile, "DevToolsActivePort")
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw IOException("The browser closed before Convx could read your sign-in")
            val lines = runCatching { file.readLines() }.getOrNull()
            if (lines != null && lines.size >= 2 && lines[0].toIntOrNull() != null) {
                return "ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}"
            }
            delay(200)
        }
        throw IOException("The browser didn't start for Convx to read your sign-in")
    }

    /** The page's `ytcfg` VISITOR_DATA and DATASYNC_ID, as the phone's JavaScript bridge reads them. */
    private suspend fun readConfig(cdp: DevTools, session: String): Pair<String, String?>? {
        val expression = "JSON.stringify((window.yt && window.yt.config_) ? " +
            "[window.yt.config_.VISITOR_DATA || null, window.yt.config_.DATASYNC_ID || null] : [null, null])"
        repeat(40) {
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
