/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Your Spotify account, linked the way Spotify's own desktop app and librespot link one: Spotify's
 * sign-in page opens in your normal browser (where you may already be signed in), and Spotify
 * hands the app a token through a one-off page on this PC. Convx never sees your password.
 *
 * The phone signs in inside a WebView and lifts the web player's token; a PC has a real browser,
 * which Spotify trusts more than an embedded one, so this uses it. The token is kept encrypted
 * for your Windows account.
 *
 * Spotify's audio stays on Spotify: songs from it play through YouTube Music, matched by title,
 * artist and length, as on the phone.
 */
object SpotifyAccount {
    private const val Tag = "Spotify"

    /** Spotify's desktop client, which accepts a loopback redirect; librespot uses it too. */
    private const val ClientId = "65b708073fc0480ea92a077233ca87bd"
    private val Ports = listOf(8898, 8989, 4381, 5588)
    private val Scopes = listOf(
        "user-read-private",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-follow-read",
        "user-top-read",
        "user-read-recently-played",
    )

    sealed interface State {
        data object SignedOut : State
        data object SigningIn : State
        data class SignedIn(val profile: SpotifyProfile?) : State
        data class Failed(val message: String) : State
    }

    @Serializable
    private data class Tokens(val access: String, val refresh: String?, val expiresAt: Long)

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File by lazy { File(AppDirs.data, "spotify.dat") }
    private val mutex = Mutex()
    private var tokens: Tokens? = null
    private var pending: CompletableDeferred<String?>? = null

    /** Reads the saved sign-in, if any. */
    fun restore() {
        AppScope.launch(Dispatchers.IO) {
            val saved = runCatching { load() }.onFailure { Log.w(Tag, "Couldn't read the saved Spotify sign-in", it) }.getOrNull()
                ?: return@launch
            tokens = saved
            _state.value = State.SignedIn(null)
            refreshProfile()
        }
    }

    /** Opens Spotify's sign-in in the browser and waits for it to come back. */
    fun signIn(openBrowser: (URI) -> Unit) {
        if (_state.value == State.SigningIn) {
            // A second click cancels the first attempt and starts again.
            pending?.complete(null)
        }
        _state.value = State.SigningIn
        AppScope.launch(Dispatchers.IO) {
            val result = runCatching { authorize(openBrowser) }
            result.onSuccess { got ->
                if (got == null) {
                    if (_state.value == State.SigningIn) _state.value = State.SignedOut
                    return@onSuccess
                }
                mutex.withLock {
                    tokens = got
                    save(got)
                }
                _state.value = State.SignedIn(null)
                refreshProfile()
            }.onFailure {
                Log.w(Tag, "Spotify sign-in failed", it)
                _state.value = State.Failed(it.message ?: "Spotify sign-in didn't finish")
            }
        }
    }

    fun cancelSignIn() {
        // Before the sign-in page is up there's nothing to cancel yet; just stop waiting for it.
        if (pending?.complete(null) != true && _state.value == State.SigningIn) _state.value = State.SignedOut
    }

    fun signOut() {
        pending?.complete(null)
        tokens = null
        runCatching { file.delete() }
        _state.value = State.SignedOut
        SpotifyApi.clearCache()
    }

    /** A token that is good for at least another minute, refreshed when it isn't. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val current = tokens ?: throw SpotifyException(401, "Connect your Spotify account first")
        if (!forceRefresh && current.expiresAt - System.currentTimeMillis() > 60_000) return@withLock current.access
        val refresh = current.refresh ?: run {
            expire()
            throw SpotifyException(401, "Your Spotify sign-in ran out. Connect again.")
        }
        val updated = withContext(Dispatchers.IO) {
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", ClientId)
                    .build(),
            )
        }.let { it.copy(refresh = it.refresh ?: refresh) }
        tokens = updated
        save(updated)
        updated.access
    }

    private fun expire() {
        tokens = null
        runCatching { file.delete() }
        _state.value = State.Failed("Your Spotify sign-in ran out. Connect again.")
    }

    private fun refreshProfile() {
        AppScope.launch(Dispatchers.IO) {
            SpotifyApi.me()
                .onSuccess { if (_state.value is State.SignedIn) _state.value = State.SignedIn(it) }
                .onFailure {
                    Log.w(Tag, "Couldn't load the Spotify profile", it)
                    if (it is SpotifyException && it.status == 401) expire()
                }
        }
    }

    private suspend fun authorize(openBrowser: (URI) -> Unit): Tokens? {
        val random = SecureRandom()
        val verifier = base64Url(ByteArray(64).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = base64Url(ByteArray(24).also(random::nextBytes))

        val (server, port) = Ports.firstNotNullOfOrNull { port ->
            runCatching { HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0) to port }.getOrNull()
        } ?: throw IOException("Every port Spotify can send you back to is busy. Close other Spotify tools and try again.")
        val redirect = "http://127.0.0.1:$port/login"
        val answer = CompletableDeferred<String?>()
        pending = answer

        server.createContext("/login") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val key = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                key to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val ok = query["state"] == state && query["code"] != null
            val page = if (ok) donePage("You're connected", "Convx can see your Spotify playlists and liked songs now. You can close this tab.")
            else donePage("Spotify didn't connect", query["error"]?.let { "Spotify said: $it. Go back to Convx and try again." } ?: "Go back to Convx and try again.")
            val bytes = page.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (query["state"] == state) answer.complete(query["code"])
        }
        server.start()
        try {
            val url = "https://accounts.spotify.com/authorize" +
                "?response_type=code" +
                "&client_id=$ClientId" +
                "&redirect_uri=${encode(redirect)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$challenge" +
                "&state=$state" +
                "&scope=${encode(Scopes.joinToString(" "))}"
            openBrowser(URI(url))
            // Long enough to type a password and pass a captcha; the button also cancels it.
            val code = withTimeoutOrNull(5 * 60_000L) { answer.await() } ?: return null
            return tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirect)
                    .add("client_id", ClientId)
                    .add("code_verifier", verifier)
                    .build(),
            )
        } finally {
            pending = null
            server.stop(1)
        }
    }

    private fun tokenRequest(body: FormBody): Tokens {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful || obj == null) {
                val error = obj?.get("error_description")?.jsonPrimitive?.contentOrNull ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
                if (obj?.get("error")?.jsonPrimitive?.contentOrNull == "invalid_grant") throw SpotifyException(401, "Your Spotify sign-in ran out. Connect again.")
                throw SpotifyException(response.code, "Spotify turned the sign-in down${error?.let { ": $it" } ?: " (${response.code})"}")
            }
            val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw SpotifyException(500, "Spotify sent no token")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            return Tokens(access, obj["refresh_token"]?.jsonPrimitive?.contentOrNull, System.currentTimeMillis() + expiresIn * 1000)
        }
    }

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun save(tokens: Tokens) {
        val plain = json.encodeToString(Tokens.serializer(), tokens).toByteArray(Charsets.UTF_8)
        // Windows encrypts it for this user account (DPAPI), as browsers keep their cookies.
        val bytes = if (windows) Crypt32Util.cryptProtectData(plain) else plain
        val temp = File(file.parentFile, "${file.name}.tmp")
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

    private fun load(): Tokens? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val plain = if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes
        return json.decodeFromString(Tokens.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun donePage(title: String, body: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Convx</title>
        <style>
          html,body{margin:0;height:100%;background:#000;color:#fff;font-family:-apple-system,"Segoe UI",system-ui,sans-serif}
          main{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 24px}
          .dot{width:56px;height:56px;border-radius:50%;background:#FA2D48;margin-bottom:24px}
          h1{font-size:28px;margin:0 0 10px}
          p{font-size:16px;color:rgba(255,255,255,.65);max-width:420px;line-height:1.45;margin:0}
        </style></head>
        <body><main><div class="dot"></div><h1>$title</h1><p>$body</p></main></body></html>
    """.trimIndent()
}

class SpotifyException(val status: Int, override val message: String) : Exception(message)
