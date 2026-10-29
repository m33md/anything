/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends what you play to Last.fm and ListenBrainz with the phone's ScrobbleManager rules: a song
 * counts once you've heard the smaller of half of it (the delay percent) and three minutes (the
 * delay), and only songs longer than 30 seconds count at all. Paused time doesn't count.
 */
object Scrobbler {
    private const val TickMs = 1000L

    /** One play of one song. */
    private class Play(val song: Song, val startedAt: Long) {
        var heardMs = 0L
        var announced = false
        var scrobbled = false
    }

    private var play: Play? = null
    private var lastPosition = 0L

    fun start() {
        AppScope.launch {
            while (isActive) {
                delay(TickMs)
                runCatching { tick() }
            }
        }
        AppScope.launch {
            Library.likeChanges.collect { (song, liked) ->
                if (AppSettings.value.lastFmSendLikes) LastFm.love(song, liked)
            }
        }
    }

    private suspend fun tick() {
        val state = PlayerController.state.value
        val song = state.current
        val position = PlayerController.position.value
        if (song == null) {
            play = null
            return
        }
        val current = play
        // A new song, or the same one started over (repeat one, or skipped back to the start).
        val restarted = current != null && current.song.id == song.id && position < 3_000 && lastPosition > 10_000
        if (current == null || current.song.id != song.id || restarted) {
            play = Play(song, System.currentTimeMillis() / 1000)
        }
        lastPosition = position
        val p = play ?: return
        if (!state.isPlaying || state.isLoading || state.error != null) return

        val settings = AppSettings.value
        if (!p.announced) {
            p.announced = true
            if (LastFm.signedIn && settings.lastFmScrobble && settings.lastFmNowPlaying) AppScope.launch { LastFm.nowPlaying(song) }
            if (ListenBrainz.token != null && settings.listenBrainzScrobble) AppScope.launch { ListenBrainz.playingNow(song) }
        }
        p.heardMs += TickMs
        if (p.scrobbled) return
        val durationMs = state.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
        if (durationMs <= settings.scrobbleMinSeconds * 1000L) return
        val needed = minOf((durationMs * settings.scrobbleDelayPercent).toLong(), settings.scrobbleDelaySeconds * 1000L)
        if (p.heardMs < needed) return
        p.scrobbled = true
        if (LastFm.signedIn && settings.lastFmScrobble) AppScope.launch { LastFm.scrobble(p.song, p.startedAt) }
        if (ListenBrainz.token != null && settings.listenBrainzScrobble) AppScope.launch { ListenBrainz.listen(p.song, p.startedAt) }
    }
}
