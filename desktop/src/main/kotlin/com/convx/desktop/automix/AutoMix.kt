/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Song
import com.convx.desktop.player.AudioEngine
import com.convx.desktop.player.PlayerState
import com.convx.desktop.data.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The phone's AutoMix (its CrossfadeController with the DJ engine's tiers): near a song's end it
 * plans the transition into the next from both songs' measurements, opens the next song silent at
 * its cue, brings it in on the outgoing song's phrase with its tempo matched, and rides the two
 * songs' levels and filters (a bass swap, or a filter sweep) until the new one has the floor.
 *
 * Runs on the player's own thread: [tick] is called from its loop, faster while a mix is on.
 */
internal object AutoMix {
    private const val Tag = "AutoMix"

    /** Something the player hands over for the next song: what to open and how loud. */
    class Prepared(
        val song: Song,
        val stream: com.convx.desktop.player.ResolvedStream,
        val source: com.convx.desktop.player.CachedAudio,
        val trackGain: Float,
    ) {
        val durationMs: Long? get() = stream.durationMs
    }

    /** What AutoMix needs of the player. */
    interface Host {
        val scope: CoroutineScope
        val engine: AudioEngine
        val engineToken: Long

        /** Finds and opens the next song as playing it would, or null if it can't be played. */
        suspend fun prepare(song: Song): Prepared?

        /** The next song took over on [deck] at [cueMs]: becomes the player's current song. */
        fun mixedInto(index: Int, prepared: Prepared, deck: AudioEngine.Deck, cueMs: Long)
    }

    private enum class Phase { Idle, Arming, Fading }

    /** How the overlap is ridden, as the phone's Render. */
    private data class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        val bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val vocalOverlap: Double = 0.0,
    )

    private var phase = Phase.Idle
    private var outgoing: AudioEngine.Deck? = null
    private var incoming: AudioEngine.Deck? = null
    private var prepared: Prepared? = null
    private var prepareJob: Job? = null
    private var nextIndex = -1
    private var nextId: String? = null
    private var outgoingToken = 0L
    private var fadeMs = 0L
    private var fadeEndMs = 0L
    private var cueMs = 0L
    private var rate = 1.0
    private var render = Render()
    private var fullDj = false
    private var armDeadline = 0L
    private var lastVerdict = ""
    private var outgoingId: String? = null
    private var planned = false

    // The phone's DjEngine creative transitions: which effect this mix wears, and where it is.
    private var creative = CreativeStyle.TRANSPARENT
    private val recentStyles = ArrayDeque<CreativeStyle>()
    private var transitionIndex = 0L
    private var effectsActive = false
    private var echoFrozen = false
    private var tapeStarted = false
    private var transitionBeatMs = FallbackBeatMs
    private var ringingDeck: AudioEngine.Deck? = null
    private var ringJob: Job? = null

    private val _mixing = MutableStateFlow(false)

    /** True while two songs are playing into each other, for the player's bar. */
    val mixing: StateFlow<Boolean> = _mixing.asStateFlow()

    val busy: Boolean get() = phase != Phase.Idle

    suspend fun tick(host: Host, s: PlayerState) {
        when (phase) {
            Phase.Idle -> consider(host, s)
            Phase.Arming -> arm(host, s)
            Phase.Fading -> fade(host)
        }
    }

    private fun consider(host: Host, s: PlayerState) {
        if (!s.isPlaying || s.isLoading || s.error != null || host.engineToken == 0L) return
        if (s.repeat == RepeatMode.ONE) return
        val current = s.current ?: return
        val index = when {
            s.index < s.queue.lastIndex -> s.index + 1
            s.repeat == RepeatMode.ALL && s.queue.size > 1 -> 0
            else -> return
        }
        val next = s.queue[index]
        val engine = host.engine
        val durationMs = engine.durationMs.takeIf { it > 0 } ?: return
        val positionMs = engine.positionMs

        // Both songs are measured while there's time; the one coming up is cached by then.
        TrackAnalyzer.request(current.id, durationMs / 1000.0)
        TrackAnalyzer.request(next.id, (next.duration ?: 0).toDouble())
        // No transition starts this far from the end, so there's nothing to plan yet.
        if (durationMs - positionMs > PlanWithinMs) return

        val settings = AppSettings.value
        val plan = planTransition(
            analysis = TrackAnalyzer.analysisFor(current.id),
            nextAnalysis = TrackAnalyzer.analysisFor(next.id),
            currentTrack = info(current, durationMs),
            nextTrack = info(next, (next.duration ?: 0) * 1000L),
            currentTime = positionMs / 1000.0,
            duration = durationMs / 1000.0,
            fadeSeconds = settings.crossfadeSeconds.coerceIn(1f, 12f).toDouble(),
            mode = CrossfadeMode.SMART,
            albumSequential = current.album?.id != null && current.album?.id == next.album?.id,
        )
        logPlan(current, next, plan)
        if (plan.blocked || plan.fadeMs <= 0L) return
        val startMs = (plan.transitionStart * 1000).roundToLong()
        if (startMs - positionMs > ArmLeadMs) return
        begin(host, s, index, next, plan, durationMs)
    }

    private fun begin(host: Host, s: PlayerState, index: Int, next: Song, plan: TransitionPlan, durationMs: Long) {
        val current = s.current ?: return
        val outgoingAnalysis = TrackAnalyzer.analysisFor(current.id)
        val incomingAnalysis = TrackAnalyzer.analysisFor(next.id)
        val djPlan = DjMixPlanner.plan(outgoingAnalysis, incomingAnalysis)
        fullDj = djPlan.tier == DjMixTier.FullDj
        val requested = if (djPlan.tier != DjMixTier.PlainCrossfade) djPlan.speed else plan.incomingPlaybackRate
        rate = requested.takeIf { it.isFinite() && it in 0.94..1.06 } ?: 1.0
        fadeMs = plan.fadeMs
        fadeEndMs = (plan.transitionEnd * 1000).roundToLong()
        cueMs = (plan.incomingCueTime * 1000).roundToLong().coerceAtLeast(0)
        render = Render(plan.transitionStyle, plan.bassSwap, plan.bassSwapFraction, plan.filterSweep, plan.vocalOverlap)
        nextIndex = index
        nextId = next.id
        outgoingId = current.id
        outgoingToken = host.engineToken
        planned = true
        creative = CreativeSelector.select(
            tier = djPlan.tier,
            outgoing = outgoingAnalysis.takeIf { it.status == TrackAnalysis.STATUS_READY },
            incoming = incomingAnalysis.takeIf { it.status == TrackAnalysis.STATUS_READY },
            creativeEnabled = creativeOn(),
            recentStyles = recentStyles.toList(),
            transitionIndex = transitionIndex,
        )
        armDeadline = System.currentTimeMillis() + ArmTimeoutMs
        phase = Phase.Arming
        Log.i(
            Tag,
            "Arming ${current.id} -> ${next.id}: ${plan.reason}, ${plan.transitionStyle}, start ${"%.1f".format(plan.transitionStart)}s " +
                "over ${fadeMs}ms, cue ${cueMs}ms, rate ${"%.3f".format(rate)}, ${djPlan.tier}, $creative",
        )
        prepareJob = host.scope.launch {
            val ready = host.prepare(next) ?: run {
                Log.w(Tag, "Couldn't open ${next.id} to mix into; it will play after")
                reset(host)
                return@launch
            }
            if (phase != Phase.Arming || nextId != next.id) return@launch
            prepared = ready
            incoming = host.engine.prepare(ready.source, cueMs, ready.durationMs, ready.trackGain).also { deck ->
                deck.rate = rate.toFloat()
                deck.mixGain = 0f
                rideFilters(0f, outgoingDeck = null, incomingDeck = deck)
                deck.filter.snapToTargets()
            }
        }
    }

    private fun arm(host: Host, s: PlayerState) {
        val stillOn = AppSettings.value.let { it.crossfade && it.autoMix }
        if (!stillOn || host.engineToken != outgoingToken || s.queue.getOrNull(nextIndex)?.id != nextId) return reset(host)
        if (!s.isPlaying) return
        val deck = incoming
        val expired = System.currentTimeMillis() > armDeadline
        if (expired && deck?.isReady != true) {
            Log.w(Tag, "The next song wasn't ready in time; no mix this time")
            return reset(host)
        }
        val positionMs = host.engine.positionMs
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - positionMs <= fadeMs + StartLeadMs
        if (!atFadePoint || deck == null || !deck.isReady) return
        startFade(host, deck)
    }

    private fun startFade(host: Host, deck: AudioEngine.Deck) {
        val ready = prepared ?: return reset(host)
        val playing = host.engine.current ?: return reset(host)
        // Everything is set on both songs before the new one starts, so its first buffer is right.
        startEffects(host, playing, deck)
        rideFilters(0f, playing, deck)
        deck.filter.snapToTargets()
        val out = host.engine.promote(deck) ?: return reset(host)
        outgoing = out
        phase = Phase.Fading
        _mixing.value = isRealMix()
        host.mixedInto(nextIndex, ready, deck, cueMs)
        Log.i(Tag, "Mixing in at ${out.positionMs}ms of the outgoing song")
    }

    private fun fade(host: Host) {
        val out = outgoing
        val into = incoming
        if (out == null || into == null || host.engineToken != into.token) return finish(host)
        val remaining = (into.durationMs - cueMs).takeIf { into.durationMs > 0 }?.coerceAtLeast(0)
        val duration = minOf(fadeMs, remaining?.div(3) ?: Long.MAX_VALUE).coerceAtLeast(1L)
        val elapsed = (into.positionMs - cueMs).coerceAtLeast(0)
        val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

        into.mixGain = sin(progress * PI.toFloat() / 2f)
        // A ringing wash fades the outgoing song's dry sound itself, so its level stays up.
        out.mixGain = if (holdsTail) 1f else cos(progress * PI.toFloat() / 2f)
        rideFilters(progress, out, into)
        driveEffects(out, into, progress)
        recoverTempo(into, progress)

        val outgoingDone = out.durationMs > 0 && out.positionMs >= out.durationMs - 30
        val switchedOff = AppSettings.value.let { !it.crossfade || !it.autoMix }
        if (progress >= 1f || outgoingDone || switchedOff) finish(host)
    }

    /** From four fifths of the way through, the incoming song eases back to its own tempo. */
    private fun recoverTempo(deck: AudioEngine.Deck, progress: Float) {
        if (rate == 1.0 || progress < TempoRecoveryFrom) return
        val recovery = ((progress - TempoRecoveryFrom) / (1f - TempoRecoveryFrom)).coerceIn(0f, 1f)
        deck.rate = (rate + (1.0 - rate) * recovery).toFloat()
    }

    /** A seek or a skip mid-mix: the outgoing song goes at once and the new one plays plainly. */
    fun interrupt(host: Host) {
        endRing(host)
        if (phase == Phase.Idle) return
        Log.i(Tag, "Mix interrupted")
        finish(host, mayRing = false)
    }

    private fun finish(host: Host, mayRing: Boolean = true) {
        val out = outgoing
        val into = incoming
        val handedOff = phase == Phase.Fading
        // The phone's ring out: an outgoing song whose echoes or reverb are the sound being heard
        // plays on silently for a few seconds so they die away instead of being cut.
        val ringing = mayRing && handedOff && holdsTail && out != null
        stopEffects(if (ringing) null else out, into)
        if (handedOff && into != null && host.engineToken == into.token) {
            into.mixGain = 1f
            into.rate = 1f
            into.filter.open()
        }
        if (out != null) {
            if (ringing) ringOut(host, out) else host.engine.retire(out)
        }
        reset(host, keepIncoming = handedOff)
    }

    private fun ringOut(host: Host, deck: AudioEngine.Deck) {
        endRing(host)
        ringingDeck = deck
        ringJob = host.scope.launch {
            delay(TailRingMs)
            ringJob = null
            endRing(host)
        }
    }

    private fun endRing(host: Host) {
        val deck = ringingDeck ?: return
        ringingDeck = null
        ringJob?.cancel()
        ringJob = null
        if (deck.effectsMade) deck.effects.stop()
        host.engine.retire(deck)
    }

    private val holdsTail: Boolean get() = effectsActive && creative in TailStyles

    private fun creativeOn(): Boolean = AppSettings.value.let { it.crossfade && it.autoMix && it.creativeTransitions }

    /** The phone's startTransitionEffects: the wash goes on the outgoing song; the incoming stays dry. */
    private fun startEffects(host: Host, out: AudioEngine.Deck, into: AudioEngine.Deck) {
        stopEffects(out, into)
        if (!creativeOn()) return
        effectsActive = true
        echoFrozen = false
        tapeStarted = false
        transitionBeatMs = measuredBeatMs(host) ?: FallbackBeatMs
        val settings = AppSettings.value
        val reverb = if (settings.djReverb == ReverbType.AUTO) ReverbPrograms[(transitionIndex % ReverbPrograms.size).toInt()] else settings.djReverb
        val delayType = if (settings.djDelay == DelayType.AUTO) DelayPrograms[(transitionIndex % DelayPrograms.size).toInt()] else settings.djDelay
        val effects = out.effects
        effects.ambience.reverbType = reverb
        effects.delay.let { echo ->
            echo.delayType = if (creative == CreativeStyle.ECHO_FREEZE && delayType == DelayType.SLAPBACK) DelayType.FILTERED else delayType
            echo.frozen = false
            echo.clearTail()
            echo.setDelayMs(if (creative != CreativeStyle.ECHO_FREEZE && delayType == DelayType.SLAPBACK) 110L else transitionBeatMs / 2)
            if (creative == CreativeStyle.ECHO_FREEZE) {
                echo.freezeFeedback = 0.72f
                echo.wetMix = 0.25f
            }
        }
        Log.i(Tag, "Effects $creative: $reverb reverb, $delayType delay, beat ${transitionBeatMs}ms")
    }

    /** The phone's driveTransitionEffects, on the mix's clock. */
    private fun driveEffects(out: AudioEngine.Deck, into: AudioEngine.Deck, progress: Float) {
        if (!effectsActive) return
        if (!creativeOn()) {
            stopEffects(out, into)
            return
        }
        val position = progress.coerceIn(0f, 1f)
        val effects = out.effects
        if (creative !in TailStyles) out.filter.stereoWidth = 1f - 0.65f * position
        if (creative in TailStyles) effects.delay.dryLevel = cos(position * (PI / 2)).toFloat()
        into.filter.pan = orbitPan(position)
        val wash = (position / WashPeakAt).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        effects.ambience.wetMix = wash * if (creative == CreativeStyle.REVERB_BLOOM) 0.5f else 0.15f
        if (creative != CreativeStyle.ECHO_FREEZE) {
            effects.delay.wetMix = wash * if (creative == CreativeStyle.DELAY_BLEND) 0.45f else 0.12f
        }
        if (creative == CreativeStyle.ECHO_FREEZE && !echoFrozen && position >= EchoFreezeAt) {
            effects.delay.frozen = true
            echoFrozen = true
        }
        if (creative == CreativeStyle.TAPE_STOP && !tapeStarted && position >= TapeStartAt) {
            val remaining = (fadeMs * (1f - position)).toLong().coerceAtLeast(100L)
            effects.tail.startTapeStop(minOf(remaining, (transitionBeatMs * TapeStopBeats).toLong()))
            tapeStarted = true
        }
    }

    /** The phone's stopTailEffects, for the songs given. */
    private fun stopEffects(vararg decks: AudioEngine.Deck?) {
        if (effectsActive && creative != CreativeStyle.TRANSPARENT) Log.i(Tag, "Effect end $creative")
        effectsActive = false
        for (deck in decks) {
            if (deck == null) continue
            deck.filter.open()
            if (deck.effectsMade) deck.effects.stop()
        }
    }

    /**
     * The incoming song enters hard on one side and circles the head, the circling slowing as it
     * goes, until it settles in the centre as the transition ends.
     */
    private fun orbitPan(progress: Float): Float {
        val side = if (transitionIndex % 2 == 0L) -1f else 1f
        val remaining = 1f - progress
        val turns = OrbitTurns * (1f - remaining * remaining)
        return side * cos(2.0 * PI * turns).toFloat() * remaining
    }

    /** The outgoing song's beat in time heard, for echoes in time with it. */
    private fun measuredBeatMs(host: Host): Long? {
        val analysis = TrackAnalyzer.analysisFor(outgoingId ?: return null)
        if (analysis.bpm <= 0 || analysis.beatConfidence < 0.2) return null
        return (60_000.0 / analysis.bpm / host.engine.tempo.coerceIn(0.25f, 2f)).toLong().coerceIn(250L, 1_500L)
    }

    private fun reset(host: Host, keepIncoming: Boolean = false) {
        prepareJob?.cancel()
        prepareJob = null
        if (!keepIncoming) incoming?.let { host.engine.retire(it) }
        if (phase == Phase.Arming) outgoing?.filter?.open()
        host.engine.current?.let { current ->
            if (!keepIncoming && current.token == outgoingToken) {
                current.mixGain = 1f
                current.filter.open()
            }
        }
        if (planned) {
            recentStyles.addFirst(creative)
            while (recentStyles.size > StyleHistory) recentStyles.removeLast()
            transitionIndex++
        }
        planned = false
        creative = CreativeStyle.TRANSPARENT
        effectsActive = false
        phase = Phase.Idle
        outgoing = null
        incoming = null
        prepared = null
        nextId = null
        outgoingId = null
        nextIndex = -1
        rate = 1.0
        render = Render()
        _mixing.value = false
    }

    /** The phone's filter rides: a sweep, a bass swap, or keeping two vocals apart. */
    private fun rideFilters(progress: Float, outgoingDeck: AudioEngine.Deck?, incomingDeck: AudioEngine.Deck?) {
        fun incoming(lowPass: Float, highPass: Float) {
            incomingDeck?.filter?.let {
                it.lowPassHz = lowPass
                it.highPassHz = highPass.coerceAtLeast(DjFilter.OpenHighPassHz)
            }
        }
        fun outgoing(lowPass: Float, highPass: Float) {
            outgoingDeck?.filter?.let {
                it.lowPassHz = lowPass
                it.highPassHz = highPass.coerceAtLeast(DjFilter.OpenHighPassHz)
            }
        }
        fun open() {
            incoming(OpenHz, OffHz)
            outgoing(OpenHz, OffHz)
        }

        fun entryHighPass(amount: Double, topHz: Double, openBy: Double): Float {
            val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
            return glide(OffHz.toDouble(), topHz, amount * remaining.pow(EntryShape)).toFloat()
        }

        fun vocalSeparation() {
            val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
            if (amount <= 0.0) return open()
            val floor = glide(OpenHz.toDouble(), VocalSeparationFloorHz, amount)
            outgoing(glide(OpenHz.toDouble(), floor, progress.toDouble().pow(FilterSweepShape)).toFloat(), OffHz)
            incoming(OpenHz, entryHighPass(amount, VocalSeparationHighPassHz, EntryOpenBy))
        }

        fun filterSweep() {
            val sweep = render.filterSweep.coerceIn(0.0, 1.0)
            if (sweep <= 0.0) return open()
            val entry = glide(OpenHz.toDouble(), FilterEntryHz, sweep)
            val floor = glide(OpenHz.toDouble(), FilterFloorHz, sweep)
            outgoing(glide(entry, floor, progress.toDouble().pow(FilterSweepShape)).toFloat(), OffHz)
            incoming(OpenHz, entryHighPass(sweep, EntryHighPassHz, EntryOpenBy))
        }

        fun bassCutoff(amount: Double): Float = glide(OffHz.toDouble(), BassSwapHz, amount).toFloat()

        fun bassSwap() {
            val swapAt = render.bassSwapFraction.coerceIn(0.05, 0.95)
            val handover = ((progress - swapAt) / BassSwapWidth * 0.5 + 0.5).coerceIn(0.0, 1.0)
            val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
            val entry = maxOf(
                bassCutoff(1.0 - ((handover - 0.15) / 0.85).coerceIn(0.0, 1.0)),
                entryHighPass(
                    1.0,
                    glide(BlendEntryHighPassHz, BlendEntryClashHighPassHz, clash),
                    BlendEntryOpenBy + (BlendEntryClashOpenBy - BlendEntryOpenBy) * clash,
                ),
            )
            incoming(OpenHz, entry)
            val from = BlendExitFrom + (BlendExitClashFrom - BlendExitFrom) * clash
            val exitAmount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
            val exitFloor = glide(BlendExitLowPassHz, BlendExitClashLowPassHz, clash)
            outgoing(glide(OpenHz.toDouble(), exitFloor, exitAmount).toFloat(), bassCutoff((handover / 0.85).coerceIn(0.0, 1.0)))
        }

        when (render.style) {
            TransitionStyle.DJ_FILTER -> filterSweep()
            TransitionStyle.DJ_BLEND -> if (render.bassSwap) bassSwap() else vocalSeparation()
            TransitionStyle.GAPLESS -> open()
            TransitionStyle.EQUAL_POWER -> if (fullDj) bassSwap() else vocalSeparation()
        }
    }

    private fun glide(from: Double, to: Double, amount: Double): Double = from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun isRealMix(): Boolean =
        render.style == TransitionStyle.DJ_BLEND || render.style == TransitionStyle.DJ_FILTER || cueMs > 0L || rate != 1.0

    private fun info(song: Song, durationMs: Long) = TransitionTrackInfo(
        id = song.id,
        durationMs = durationMs,
        title = song.title,
        artist = song.artistText,
    )

    private fun logPlan(current: Song, next: Song, plan: TransitionPlan) {
        val verdict = "${current.id}->${next.id} ${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}|blocked=${plan.blocked}"
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        Log.i(Tag, "Plan $verdict policy=${plan.policyReasons.joinToString(",")}")
    }

    private const val ArmLeadMs = 4_000L
    private const val PlanWithinMs = 75_000L
    private const val ArmTimeoutMs = 12_000L

    /** Starting a touch early makes up for the new song's sound card buffer filling. */
    private const val StartLeadMs = 20L
    private const val TempoRecoveryFrom = 0.8f

    private const val FallbackBeatMs = 500L
    private const val TailRingMs = 8_000L
    private const val TapeStopBeats = 1.5f
    private const val EchoFreezeAt = 0.6f
    private const val WashPeakAt = 0.75f
    private const val OrbitTurns = 1.5f
    private const val TapeStartAt = 0.82f
    private const val StyleHistory = 6
    private val ReverbPrograms = arrayOf(ReverbType.ROOM, ReverbType.PLATE, ReverbType.HALL)
    private val DelayPrograms = arrayOf(DelayType.STEREO, DelayType.PING_PONG, DelayType.TAPE, DelayType.FILTERED, DelayType.SLAPBACK)
    private val TailStyles = setOf(CreativeStyle.REVERB_BLOOM, CreativeStyle.DELAY_BLEND, CreativeStyle.ECHO_FREEZE)

    private const val OpenHz = 20_000f
    private const val OffHz = 20f
    private const val FilterEntryHz = 7_000.0
    private const val FilterFloorHz = 300.0
    private const val BassSwapHz = 200.0
    private const val BassSwapWidth = 0.10
    private const val FilterSweepShape = 0.75
    private const val EntryHighPassHz = 1_200.0
    private const val EntryOpenBy = 0.6
    private const val EntryShape = 0.35
    private const val VocalSeparationFloorHz = 1_600.0
    private const val VocalSeparationHighPassHz = 700.0
    private const val BlendEntryHighPassHz = 520.0
    private const val BlendEntryOpenBy = 0.45
    private const val BlendEntryClashHighPassHz = 950.0
    private const val BlendEntryClashOpenBy = 0.7
    private const val BlendExitClashFrom = 0.12
    private const val BlendExitClashLowPassHz = 1_100.0
    private const val BlendExitFrom = 0.3
    private const val BlendExitLowPassHz = 2_200.0
}

/** The phone's DjMixPlanner tiers: how far the two songs' grids and keys let a mix go. */
internal enum class DjMixTier { FullDj, SmartCrossfade, PlainCrossfade }

internal class DjMixPlan(val tier: DjMixTier, val speed: Double = 1.0)

internal object DjMixPlanner {
    private const val MinConfidenceForSmart = 0.20
    private const val MinConfidenceForFull = 0.45
    private const val MaxStretch = 0.06
    private const val MinKeyConfidence = 0.55
    private val OctaveFactors = doubleArrayOf(0.5, 1.0, 2.0)

    fun plan(outgoing: TrackAnalysis, incoming: TrackAnalysis): DjMixPlan {
        if (outgoing.bpm <= 0 || incoming.bpm <= 0) return DjMixPlan(DjMixTier.PlainCrossfade)
        val minConfidence = minOf(outgoing.beatConfidence, incoming.beatConfidence).coerceAtLeast(0.0)
        if (minConfidence < MinConfidenceForSmart) return DjMixPlan(DjMixTier.PlainCrossfade)
        var bestSpeed = 1.0
        var bestStretch = Double.MAX_VALUE
        for (factor in OctaveFactors) {
            val speed = outgoing.bpm / (incoming.bpm * factor)
            val stretch = abs(speed - 1.0)
            if (stretch < bestStretch) {
                bestStretch = stretch
                bestSpeed = speed
            }
        }
        return when {
            minConfidence >= MinConfidenceForFull && bestStretch <= MaxStretch && compatible(outgoing, incoming) -> DjMixPlan(DjMixTier.FullDj, bestSpeed)
            bestStretch <= MaxStretch -> DjMixPlan(DjMixTier.SmartCrossfade, bestSpeed)
            else -> DjMixPlan(DjMixTier.PlainCrossfade)
        }
    }

    private val PitchClasses = mapOf(
        "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3, "E" to 4, "F" to 5, "F♯" to 6,
        "G♭" to 6, "G" to 7, "G♯" to 8, "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
    )

    /** Camelot wheel neighbours, as the phone's MusicalKey; an unsure key never refuses. */
    private fun compatible(a: TrackAnalysis, b: TrackAnalysis): Boolean {
        val left = camelot(a) ?: return true
        val right = camelot(b) ?: return true
        if (left.first == right.first) return true
        val distance = Math.floorMod(left.first - right.first, 12)
        return (distance == 1 || distance == 11) && left.second == right.second
    }

    private fun camelot(analysis: TrackAnalysis): Pair<Int, Boolean>? {
        if (analysis.keyConfidence < MinKeyConfidence) return null
        val parts = analysis.key.trim().split(' ')
        val pitch = PitchClasses[parts.firstOrNull()] ?: return null
        val minor = parts.getOrNull(1) == "minor"
        val relativeMajor = if (minor) (pitch + 3) % 12 else pitch
        return ((relativeMajor * 7 + 7) % 12) + 1 to minor
    }
}
