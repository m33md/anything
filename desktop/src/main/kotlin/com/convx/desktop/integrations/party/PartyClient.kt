/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.party

import com.convx.desktop.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** A refusal from the party server, or a transport failure turned into one. */
class PartyException(val code: String, message: String) : IOException(message)

/** REST and WebSocket client for the phone's party server (jam/JamClient.kt). */
object PartyClient {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val jsonType = "application/json".toMediaType()

    // The socket stays open, so no read timeout; Http's proxy settings still apply.
    private val http by lazy {
        Http.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** A free-tier server sleeps when idle; this wakes it while the user types a name and a code. */
    suspend fun warm(baseUrl: String) {
        withContext(Dispatchers.IO) {
            runCatching { http.newCall(Request.Builder().url("${base(baseUrl)}/api/time").build()).execute().close() }
        }
    }

    suspend fun create(baseUrl: String, request: JoinRequest): PartyMembership =
        post("${base(baseUrl)}/api/parties", json.encodeToString(JoinRequest.serializer(), request))

    suspend fun join(baseUrl: String, code: String, request: JoinRequest): PartyMembership =
        post("${base(baseUrl)}/api/parties/${code.trim()}/join", json.encodeToString(JoinRequest.serializer(), request))

    suspend fun leave(baseUrl: String, code: String, token: String) {
        withContext(Dispatchers.IO) { leaveNow(baseUrl, code, token) }
    }

    /** [leave] on the calling thread, for quitting. */
    fun leaveNow(baseUrl: String, code: String, token: String) {
        val request = Request.Builder()
            .url("${base(baseUrl)}/api/parties/$code/leave")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(jsonType))
            .build()
        // Quitting waits on this, so it gives up quickly.
        runCatching { http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build().newCall(request).execute().close() }
    }

    /** Opens the party socket; the token travels in the handshake header, never the URL. */
    fun open(baseUrl: String, code: String, token: String, listener: WebSocketListener): WebSocket {
        val wsUrl = base(baseUrl).replaceFirst("http", "ws") + "/ws/parties/$code"
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
        return http.newWebSocket(request, listener)
    }

    fun send(socket: WebSocket, frame: JsonObject): Boolean = socket.send(frame.toString())

    private suspend fun post(url: String, body: String): PartyMembership = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toRequestBody(jsonType)).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PartyException("unreachable", "Couldn't reach the party server")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val error = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw PartyException(error?.code ?: "http_${it.code}", error?.message?.ifBlank { null } ?: describe(it.code))
            }
            json.decodeFromString(PartyMembership.serializer(), text)
        }
    }

    private fun describe(status: Int) = when (status) {
        404 -> "No party with that code"
        409 -> "That party is full"
        422 -> "Name and device are required"
        429 -> "Too many requests, try again in a minute"
        else -> "The party server answered $status"
    }

    private fun base(url: String): String = url.trim().trimEnd('/')
}

/**
 * This PC's offset from the party server's clock, measured the way NTP does: each pong echoes the
 * ping's send time with the server's, and the sample with the shortest round trip is the least wrong.
 */
class PartyClock(private val maxSamples: Int = 12) {
    private data class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun observe(clientSentMs: Long, serverMs: Long, receivedMs: Long) {
        val rtt = receivedMs - clientSentMs
        if (rtt < 0) return
        samples.addLast(Sample(rtt, serverMs - (clientSentMs + receivedMs) / 2))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /** `serverNow - thisPcNow`; 0 until the first pong lands. */
    @Synchronized
    fun offsetMs(): Long = samples.minByOrNull { it.rtt }?.offset ?: 0L

    fun serverNow(now: Long): Long = now + offsetMs()

    @Synchronized
    fun reset() = samples.clear()
}

object PartySync {
    /** Where the party is right now: the anchored position plus the time since, while playing. */
    fun positionAt(playback: PartyPlayback, serverNow: Long): Long =
        if (playback.isPlaying) playback.positionMs + max(0L, serverNow - playback.anchorMs) else playback.positionMs

    /** How long to wait before a resume anchored slightly in the future should start. */
    fun startDelayMs(playback: PartyPlayback, serverNow: Long): Long =
        if (playback.isPlaying) max(0L, playback.anchorMs - serverNow) else 0L
}
