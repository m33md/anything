/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.data.AudioQuality
import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** A playable audio stream for one track, with what the quality badge needs to describe it. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val loudnessDb: Double?,
    val durationMs: Long?,
    val client: String,
    val expiresAt: Long,
) {
    val codec: String
        get() = mimeType.substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.').ifBlank {
            mimeType.substringAfter('/').substringBefore(';')
        }

    val container: String get() = if (mimeType.contains("webm")) "webm" else "m4a"

    /** "OPUS • 160kbps • 48.0kHz", the phone's quality-badge wording. */
    val badge: String
        get() = buildList {
            add(codec.uppercase().replace("MP4A", "AAC"))
            if (bitrate > 0) add("${bitrate / 1000}kbps")
            sampleRate?.let { add("%.1fkHz".format(it / 1000f)) }
        }.joinToString(" • ")
}

/**
 * Turns a video id into a stream URL. This is the phone's YTPlayerUtils chain minus
 * what needs an Android WebView (PoTokens): clients that hand out direct URLs come
 * first, and the signature-cipher clients go through NewPipe's JS deobfuscation.
 */
object StreamResolver {
    private const val Tag = "StreamResolver"

    /** Same lead client as the phone: fast, no PoToken. Then the phone's fallbacks that work without one. */
    private val clients: List<YouTubeClient> = listOf(
        ANDROID_VR_1_43_32,
        VISIONOS,
        IOS,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_65_10,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_VR_NO_AUTH,
        MOBILE,
    )

    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    /** Keep a little headroom so a URL never expires mid-download. */
    private const val ExpiryMarginMs = 10 * 60 * 1000L

    fun cached(videoId: String): ResolvedStream? =
        cache[videoId]?.takeIf { it.expiresAt - ExpiryMarginMs > System.currentTimeMillis() }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false): Result<ResolvedStream> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) cached(videoId)?.let { return@withContext Result.success(it) }
            var lastReason: String? = null
            var signatureTimestamp: Int? = null
            var signatureTimestampFetched = false

            for (client in clients) {
                currentCoroutineContext().ensureActive()
                if (client.loginRequired && YouTube.cookie == null) continue
                try {
                    val timestamp = if (client.useSignatureTimestamp) {
                        if (!signatureTimestampFetched) {
                            signatureTimestamp = NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()
                            signatureTimestampFetched = true
                        }
                        signatureTimestamp
                    } else null

                    val response = YouTube.player(videoId, null, client, timestamp, null).getOrNull()
                    if (response == null) {
                        lastReason = "No response from ${client.clientName}"
                        continue
                    }
                    if (response.playabilityStatus.status != "OK") {
                        lastReason = response.playabilityStatus.reason ?: response.playabilityStatus.status
                        Log.i(Tag, "$videoId: ${client.clientName} -> ${response.playabilityStatus.status} ($lastReason)")
                        continue
                    }
                    val formats = rankedAudioFormats(response.streamingData?.adaptiveFormats.orEmpty(), quality)
                    for (format in formats.take(3)) {
                        currentCoroutineContext().ensureActive()
                        val url = format.url?.takeIf { it.isNotBlank() }
                            ?: NewPipeExtractor.getStreamUrl(format, videoId)
                            ?: continue
                        val length = probe(url) ?: continue
                        val stream = ResolvedStream(
                            videoId = videoId,
                            url = url,
                            mimeType = format.mimeType,
                            bitrate = format.averageBitrate ?: format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength?.takeIf { it > 0 } ?: length,
                            loudnessDb = format.loudnessDb,
                            durationMs = format.approxDurationMs?.toLongOrNull()
                                ?: response.videoDetails?.lengthSeconds?.toLongOrNull()?.times(1000),
                            client = client.clientName,
                            expiresAt = System.currentTimeMillis() +
                                (response.streamingData?.expiresInSeconds ?: 21600) * 1000L,
                        )
                        Log.i(Tag, "$videoId: playing via ${client.clientName}, ${stream.badge}")
                        cache[videoId] = stream
                        return@withContext Result.success(stream)
                    }
                    lastReason = "${client.clientName} returned no reachable audio"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastReason = e.message
                    Log.w(Tag, "$videoId: ${client.clientName} failed", e)
                }
            }
            Result.failure(IllegalStateException(lastReason ?: "This song can't be played right now"))
        }

    private fun rankedAudioFormats(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: AudioQuality,
    ): List<PlayerResponse.StreamingData.Format> {
        val audio = formats.filter { it.isAudio && it.isOriginal }.ifEmpty { formats.filter { it.isAudio } }
        return when (quality) {
            AudioQuality.LOW -> audio.sortedBy { it.bitrate }
            // Opus beats AAC at the same bitrate; prefer it, then the highest bitrate.
            AudioQuality.HIGH, AudioQuality.AUTO -> audio.sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.bitrate }
                    .thenByDescending { it.mimeType.contains("opus") },
            )
        }
    }

    /**
     * A one-byte ranged GET: YouTube can accept a HEAD for a URL it won't serve. Returns
     * the full length from Content-Range, or null when the URL doesn't serve bytes.
     */
    private fun probe(url: String): Long? = runCatching {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(Tag, "probe -> HTTP ${response.code}")
                return null
            }
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: -1L
        }
    }.getOrNull()
}
