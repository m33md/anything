/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val error: String? = null,
    val stream: ResolvedStream? = null,
    /** "Playing from" label: an album, a playlist, "Radio". */
    val queueTitle: String? = null,
) {
    val current: Song? get() = queue.getOrNull(index)
    val upNext: List<Song> get() = if (index < 0) queue else queue.drop(index + 1)
    val hasNext: Boolean get() = index < queue.lastIndex || repeat == RepeatMode.ALL
}

/**
 * Queue, transport and stream lifecycle. All mutations run on one confined dispatcher, so
 * engine callbacks from the audio thread and clicks from the UI never race each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object PlayerController : AudioEngine.Listener {
    private const val Tag = "Player"
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val engine = AudioEngine(this)

    private val _state = MutableStateFlow(
        AppSettings.value.let { PlayerState(shuffle = it.shuffle, repeat = it.repeat, volume = it.volume, muted = it.muted) },
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** The order the queue had before shuffle, so turning shuffle off restores it. */
    private var unshuffled: List<Song>? = null
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var engineToken = 0L
    private var radioContinuation: String? = null
    private var radioEndpoint: WatchEndpoint? = null
    private var consecutiveFailures = 0
    private var retriedCurrent = false
    private var playStartedAt = 0L

    init {
        applyVolume()
        scope.launch {
            while (isActive) {
                val s = _state.value
                if (s.current != null && engineToken != 0L) _position.value = engine.positionMs
                delay(if (s.isPlaying) 100 else 400)
            }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                persistQueue()
            }
        }
    }

    // Public API (safe from any thread)

    fun playSongs(songs: List<Song>, startIndex: Int = 0, title: String? = null, shuffle: Boolean? = null) = scope.launch {
        if (songs.isEmpty()) return@launch
        radioContinuation = null
        radioEndpoint = null
        val wantShuffle = shuffle ?: _state.value.shuffle
        var queue = songs
        var index = startIndex.coerceIn(songs.indices)
        unshuffled = null
        if (wantShuffle) {
            unshuffled = songs
            val first = if (shuffle == true && startIndex == 0) songs.random() else songs[index]
            queue = listOf(first) + (songs - first).shuffled()
            index = 0
        }
        _state.update { it.copy(queue = queue, index = index, queueTitle = title, shuffle = wantShuffle, error = null) }
        persistSettings()
        loadCurrent(0, playWhenReady = true)
    }

    /** Plays [song] and fills the queue with YouTube Music's radio for it. */
    fun startRadio(song: Song, endpoint: WatchEndpoint? = null, title: String? = "Radio") = scope.launch {
        radioContinuation = null
        unshuffled = null
        val watch = endpoint ?: WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")
        radioEndpoint = watch
        _state.update { it.copy(queue = listOf(song), index = 0, queueTitle = title, error = null) }
        loadCurrent(0, playWhenReady = true)
        extendRadio(watch, replaceAfterCurrent = true)
    }

    /** Starts a radio/mix from a watch endpoint (e.g. a playlist's radio button) without a known first song. */
    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null) = scope.launch {
        val result = YouTube.next(endpoint).getOrNull() ?: run {
            _state.update { it.copy(error = "Couldn't start this mix") }
            return@launch
        }
        val songs = result.items.map { it.toSong() }
        if (songs.isEmpty()) return@launch
        unshuffled = null
        radioEndpoint = result.endpoint
        radioContinuation = result.continuation
        val index = (result.currentIndex ?: 0).coerceIn(songs.indices)
        _state.update { it.copy(queue = songs, index = index, queueTitle = title ?: result.title, error = null) }
        loadCurrent(0, playWhenReady = true)
    }

    fun playNext(songs: List<Song>) = scope.launch {
        if (songs.isEmpty()) return@launch
        val s = _state.value
        if (s.current == null) {
            playSongs(songs)
            return@launch
        }
        val queue = s.queue.toMutableList().apply { addAll(s.index + 1, songs) }
        unshuffled = unshuffled?.plus(songs)
        _state.update { it.copy(queue = queue) }
        prefetchNext()
    }

    fun addToQueue(songs: List<Song>) = scope.launch {
        if (songs.isEmpty()) return@launch
        if (_state.value.current == null) {
            playSongs(songs)
            return@launch
        }
        unshuffled = unshuffled?.plus(songs)
        _state.update { it.copy(queue = it.queue + songs) }
        prefetchNext()
    }

    fun removeFromQueue(position: Int) = scope.launch {
        val s = _state.value
        if (position !in s.queue.indices || position == s.index) return@launch
        val removed = s.queue[position]
        val queue = s.queue.toMutableList().apply { removeAt(position) }
        unshuffled = unshuffled?.let { list -> list - removed }
        _state.update { it.copy(queue = queue, index = if (position < s.index) s.index - 1 else s.index) }
        prefetchNext()
    }

    fun moveInQueue(from: Int, target: Int) = scope.launch {
        val s = _state.value
        if (from !in s.queue.indices) return@launch
        // Dragged rows stay in Next up: past either end they stop at the edge.
        val to = if (from > s.index) target.coerceIn(s.index + 1, s.queue.lastIndex) else target.coerceIn(s.queue.indices)
        if (from == to) return@launch
        val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val index = when (s.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) s.index - 1 else s.index + 1
            else -> s.index
        }
        _state.update { it.copy(queue = queue, index = index) }
        prefetchNext()
    }

    fun clearUpNext() = scope.launch {
        val s = _state.value
        if (s.current == null) return@launch
        unshuffled = null
        _state.update { it.copy(queue = s.queue.take(s.index + 1)) }
    }

    fun skipTo(position: Int) = scope.launch {
        val s = _state.value
        if (position !in s.queue.indices) return@launch
        _state.update { it.copy(index = position, error = null) }
        loadCurrent(0, playWhenReady = true)
    }

    fun togglePlay() = scope.launch {
        val s = _state.value
        when {
            s.current == null -> Unit
            s.isPlaying -> pauseNow()
            engineToken == 0L || s.error != null -> loadCurrent(_position.value, playWhenReady = true)
            else -> {
                engine.play()
                _state.update { it.copy(isPlaying = true) }
            }
        }
    }

    fun play() = scope.launch { if (!_state.value.isPlaying) togglePlay() }

    fun pause() = scope.launch { pauseNow() }

    fun next() = scope.launch { advance(userInitiated = true) }

    fun previous() = scope.launch {
        val s = _state.value
        if (s.current == null) return@launch
        if (_position.value > 3_000 || s.index == 0) {
            seekNow(0)
        } else {
            _state.update { it.copy(index = s.index - 1, error = null) }
            loadCurrent(0, playWhenReady = true)
        }
    }

    fun seekTo(positionMs: Long) = scope.launch { seekNow(positionMs) }

    fun seekBy(deltaMs: Long) = scope.launch { seekNow((_position.value + deltaMs).coerceAtLeast(0)) }

    fun setVolume(volume: Float) = scope.launch {
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f), muted = false) }
        applyVolume()
        persistSettings()
    }

    fun toggleMute() = scope.launch {
        _state.update { it.copy(muted = !it.muted) }
        applyVolume()
        persistSettings()
    }

    fun toggleShuffle() = scope.launch {
        val s = _state.value
        if (!s.shuffle) {
            unshuffled = s.queue
            val current = s.current
            val queue = if (current == null) s.queue.shuffled()
            else s.queue.take(s.index + 1) + s.queue.drop(s.index + 1).shuffled()
            _state.update { it.copy(shuffle = true, queue = queue) }
        } else {
            val original = unshuffled
            val current = s.current
            if (original != null && current != null) {
                val pending = s.queue.toSet()
                val restored = original.filter { it in pending } + s.queue.filter { it !in original }
                _state.update { it.copy(shuffle = false, queue = restored, index = restored.indexOf(current).coerceAtLeast(0)) }
            } else {
                _state.update { it.copy(shuffle = false) }
            }
            unshuffled = null
        }
        persistSettings()
        prefetchNext()
    }

    fun cycleRepeat() = scope.launch {
        _state.update {
            it.copy(repeat = when (it.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            })
        }
        persistSettings()
    }

    fun dismissError() = scope.launch { _state.update { it.copy(error = null) } }

    /** Restores the last session's queue, paused, at the saved position. */
    fun restore() = scope.launch {
        val settings = AppSettings.value
        if (settings.lastQueue.isEmpty()) return@launch
        val index = settings.lastIndex.coerceIn(settings.lastQueue.indices)
        _state.update { it.copy(queue = settings.lastQueue, index = index) }
        _position.value = settings.lastPositionMs
    }

    fun shutdown() {
        persistQueue()
        engine.stop()
    }

    // Engine callbacks (audio thread)

    override fun onReady(token: Long, durationMs: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            consecutiveFailures = 0
            _state.update { it.copy(isLoading = false, durationMs = durationMs.takeIf { d -> d > 0 } ?: it.durationMs) }
            prefetchNext()
        }
    }

    override fun onEnded(token: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            if (_state.value.repeat == RepeatMode.ONE) {
                loadCurrent(0, playWhenReady = true)
            } else {
                advance(userInitiated = false)
            }
        }
    }

    override fun onError(token: Long, error: Throwable, positionMs: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            val song = _state.value.current ?: return@launch
            if (!retriedCurrent) {
                // Most failures are an expired or rejected URL: resolve again and carry on from here.
                retriedCurrent = true
                Log.w(Tag, "Retrying ${song.id} at $positionMs after: ${error.message}")
                StreamResolver.invalidate(song.id)
                AudioCache.cancel(song.id)
                loadCurrent(positionMs, playWhenReady = true, forceRefresh = true)
            } else {
                failCurrent(error.message ?: "Playback failed")
            }
        }
    }

    // Internals (confined)

    private fun pauseNow() {
        engine.pause()
        _state.update { it.copy(isPlaying = false) }
        persistQueue()
    }

    private fun seekNow(positionMs: Long) {
        val s = _state.value
        if (s.current == null) return
        val target = if (s.durationMs > 0) positionMs.coerceIn(0, s.durationMs - 500) else positionMs
        _position.value = target
        if (engineToken == 0L) loadCurrent(target, playWhenReady = s.isPlaying) else engine.seek(target)
    }

    private suspend fun advance(userInitiated: Boolean) {
        val s = _state.value
        if (s.current == null) return
        when {
            s.index < s.queue.lastIndex -> {
                _state.update { it.copy(index = s.index + 1, error = null) }
                loadCurrent(0, playWhenReady = true)
            }
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> {
                _state.update { it.copy(index = 0, error = null) }
                loadCurrent(0, playWhenReady = true)
            }
            AppSettings.value.autoRadio -> {
                // Keep the music going like the phone does: extend with radio from the last song.
                val endpoint = radioEndpoint ?: WatchEndpoint(videoId = s.current!!.id, playlistId = "RDAMVM${s.current!!.id}")
                val before = s.queue.size
                extendRadio(endpoint, replaceAfterCurrent = false)
                if (_state.value.queue.size > before) {
                    _state.update { it.copy(index = s.index + 1, error = null) }
                    loadCurrent(0, playWhenReady = true)
                } else if (!userInitiated) stopAtEnd()
            }
            !userInitiated -> stopAtEnd()
        }
    }

    private fun stopAtEnd() {
        engine.stop()
        engineToken = 0L
        _position.value = 0
        _state.update { it.copy(isPlaying = false, isLoading = false) }
    }

    private suspend fun extendRadio(endpoint: WatchEndpoint, replaceAfterCurrent: Boolean) {
        val result = YouTube.next(endpoint, radioContinuation).getOrNull() ?: return
        radioContinuation = result.continuation
        radioEndpoint = result.endpoint
        val s = _state.value
        val known = s.queue.mapTo(HashSet()) { it.id }
        val fresh = result.items.map { it.toSong() }.filter { it.id !in known }
        if (fresh.isEmpty()) return
        val queue = if (replaceAfterCurrent) s.queue.take(s.index + 1) + fresh else s.queue + fresh
        _state.update { it.copy(queue = queue) }
        prefetchNext()
    }

    private fun loadCurrent(startMs: Long, playWhenReady: Boolean, forceRefresh: Boolean = false) {
        val song = _state.value.current ?: return
        if (!forceRefresh) retriedCurrent = false
        loadJob?.cancel()
        engine.stop()
        engineToken = 0L
        _position.value = startMs
        _state.update {
            it.copy(
                isLoading = true,
                isPlaying = playWhenReady,
                error = null,
                durationMs = (song.duration ?: 0) * 1000L,
                stream = null,
            )
        }
        loadJob = scope.launch {
            val quality = AppSettings.value.audioQuality
            val stream = StreamResolver.resolve(song.id, quality, forceRefresh).getOrElse { error ->
                Log.e(Tag, "Couldn't resolve ${song.id}", error)
                failCurrent(error.message ?: "This song can't be played right now")
                return@launch
            }
            if (_state.value.current?.id != song.id) return@launch
            val source = AudioCache.open(stream)
            engineToken = engine.load(source, startMs, playWhenReady, stream.durationMs)
            _state.update { it.copy(stream = stream, durationMs = stream.durationMs ?: it.durationMs) }
            if (startMs == 0L) {
                playStartedAt = System.currentTimeMillis()
                Library.recordPlay(song)
            }
        }
    }

    private suspend fun failCurrent(message: String) {
        consecutiveFailures++
        engine.stop()
        engineToken = 0L
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
        // Skip past a dead track, but don't spin through a whole queue that can't play.
        if (consecutiveFailures < 3 && _state.value.index < _state.value.queue.lastIndex) {
            delay(1500)
            if (_state.value.error == message) advance(userInitiated = false)
        }
    }

    private fun prefetchNext() {
        prefetchJob?.cancel()
        val s = _state.value
        val next = s.queue.getOrNull(s.index + 1) ?: return
        prefetchJob = scope.launch {
            delay(3_000)
            if (AudioCache.isCached(next.id)) return@launch
            StreamResolver.resolve(next.id, AppSettings.value.audioQuality).onSuccess { AudioCache.prefetch(it) }
        }
    }

    private fun applyVolume() {
        val s = _state.value
        // Perceptual curve: the slider's midpoint sounds like half volume.
        engine.gain = if (s.muted) 0f else s.volume * s.volume
    }

    private fun persistSettings() {
        val s = _state.value
        AppSettings.update { it.copy(volume = s.volume, muted = s.muted, shuffle = s.shuffle, repeat = s.repeat) }
    }

    private fun persistQueue() {
        val s = _state.value
        // Keep the saved queue bounded; radio queues grow without limit.
        val start = (s.index - 50).coerceAtLeast(0)
        val queue = s.queue.drop(start).take(300)
        AppSettings.update {
            it.copy(lastQueue = queue, lastIndex = (s.index - start).coerceAtLeast(0), lastPositionMs = _position.value)
        }
    }
}
