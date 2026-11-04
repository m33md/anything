/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.recognition

import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Recognition
import com.music.shazamkit.Shazam
import com.music.shazamkit.models.RecognitionResult
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's MusicRecognitionService on a PC: ten seconds from a sound input, made into a Shazam
 * signature by the phone's own generator, and sent with the phone's Shazam client.
 */
object Recognizer {
    private const val Tag = "Recognizer"

    /** The phone's recording length: the original recognizer's last, 10-second try. */
    const val ListenMs = 10_000L
    private const val Rate = 16_000

    /** What Windows does when it won't let desktop apps use the microphone: the input is silent. */
    const val SilentInput = "Convx heard nothing. Check that a microphone is plugged in and that Windows lets desktop apps use it."

    private val _status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val status: StateFlow<RecognitionStatus> = _status.asStateFlow()

    /** How loud the input is while listening, 0 to 1, for the pulsing circle. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** The inputs Java Sound can record from, by name, for the picker. */
    fun inputs(): List<String> = AudioSystem.getMixerInfo()
        .filter { info ->
            runCatching { AudioSystem.getMixer(info).targetLineInfo.any { it.lineClass == TargetDataLine::class.java } }.getOrDefault(false)
        }
        .map { it.name }
        // Java lists every input twice on Windows, once as the "Port" mixer; only one can record.
        .filterNot { it.startsWith("Port ") }
        .distinct()

    fun reset() {
        _status.value = RecognitionStatus.Ready
        _level.value = 0f
    }

    suspend fun recognize(): RecognitionStatus = withContext(Dispatchers.IO) {
        _status.value = RecognitionStatus.Listening
        try {
            val pcm = record()
            currentCoroutineContext().ensureActive()
            _status.value = RecognitionStatus.Processing
            val signature = runCatching { ShazamSignatureGenerator.fromI16(pcm) }.getOrElse {
                return@withContext RecognitionStatus.Error("Failed to generate fingerprint: ${it.message}").also { s -> _status.value = s }
            }
            val sampleMs = (pcm.size / 2) * 1000L / Rate
            Shazam.recognize(signature, sampleMs).fold(
                onSuccess = { result ->
                    save(result)
                    _status.value = RecognitionStatus.Success(result)
                },
                onFailure = { error ->
                    val message = error.message ?: "Unknown error"
                    _status.value = if (message.contains("No match", ignoreCase = true)) {
                        RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
                    } else {
                        RecognitionStatus.Error(message)
                    }
                },
            )
            _status.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "Recognition failed: ${e.message}")
            RecognitionStatus.Error(e.message ?: "Recognition failed").also { _status.value = it }
        } finally {
            _level.value = 0f
        }
    }

    /** The phone keeps every song it finds, the same one again included. */
    private fun save(result: RecognitionResult) = Library.addRecognition(
        Recognition(
            trackId = result.trackId,
            title = result.title,
            artist = result.artist,
            album = result.album,
            coverArt = result.coverArtUrl,
            coverArtHq = result.coverArtHqUrl,
            genre = result.genre,
            releaseDate = result.releaseDate,
            label = result.label,
            shazamUrl = result.shazamUrl,
            appleMusicUrl = result.appleMusicUrl,
            spotifyUrl = result.spotifyUrl,
            isrc = result.isrc,
            youtubeVideoId = result.youtubeVideoId,
        ),
    )

    /** Ten seconds of the chosen input as 16 kHz mono 16-bit PCM, what the signature needs. */
    private suspend fun record(): ByteArray {
        val (line, format) = openInput()
        val frameBytes = format.frameSize
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(frameBytes * (format.sampleRate / 20).toInt())
        var peak = 0
        try {
            line.start()
            val start = System.nanoTime()
            while ((System.nanoTime() - start) / 1_000_000 < ListenMs && currentCoroutineContext().isActive) {
                val read = line.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                out.write(buffer, 0, read)
                // Loudness of this chunk, for the circle, and the loudest sample, to catch a muted input.
                var sum = 0.0
                var count = 0
                var i = 0
                while (i + 1 < read) {
                    val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                    sum += s.toDouble() * s
                    if (abs(s) > peak) peak = abs(s)
                    count++
                    i += 2
                }
                if (count > 0) _level.value = (sqrt(sum / count) / 6000.0).toFloat().coerceIn(0f, 1f)
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            _level.value = 0f
        }
        currentCoroutineContext().ensureActive()
        if (peak < 8) error(SilentInput)
        val mono = toMono(out.toByteArray(), format.channels)
        return if (format.sampleRate.toInt() == Rate) mono else resample(mono, format.sampleRate.toInt(), Rate)
    }

    /** The chosen input at 16 kHz mono if it records that, else at a rate it does; stereo-only inputs are mixed down. */
    private fun openInput(): Pair<TargetDataLine, AudioFormat> {
        val wanted = AppSettings.value.recognitionInput
        val mixer = wanted?.let { name -> AudioSystem.getMixerInfo().firstOrNull { it.name == name } }?.let { AudioSystem.getMixer(it) }
        if (wanted != null && mixer == null) Log.w(Tag, "Input \"$wanted\" is gone; using the default")
        var failure: Exception? = null
        for (rate in listOf(Rate, 48_000, 44_100)) {
            for (channels in listOf(1, 2)) {
                val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, format)
                try {
                    val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                    line.open(format, format.frameSize * rate / 5)
                    Log.i(Tag, "Listening with ${mixer?.mixerInfo?.name ?: "the default input"} at $rate Hz, $channels channel(s)")
                    return line to format
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        Log.w(Tag, "No input could record: ${failure?.message}")
        error("No microphone was found. Plug one in, or pick another input.")
    }

    private fun toMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return pcm
        val frames = pcm.size / (2 * channels)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                sum += (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            }
            val s = sum / channels
            out[f * 2] = s.toByte()
            out[f * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }

    /** Windowed-sinc resampling to [to], filtered below its Nyquist so nothing folds back into the bands Shazam reads. */
    internal fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
        val input = ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() }
        val step = from.toDouble() / to
        val cutoff = 0.92 / step
        val half = 24
        val count = (input.size / step).toInt()
        val out = ByteArray(count * 2)
        for (n in 0 until count) {
            val t = n * step
            val center = floor(t).toInt()
            var sum = 0.0
            var weight = 0.0
            for (k in center - half + 1..center + half) {
                if (k < 0 || k >= input.size) continue
                val x = t - k
                val sinc = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * x / half)
                val w = sinc * window
                sum += input[k] * w
                weight += w
            }
            val s = if (weight != 0.0) (sum / weight).toInt().coerceIn(-32768, 32767) else 0
            out[n * 2] = s.toByte()
            out[n * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }
}
