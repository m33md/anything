/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.party

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The party server's wire format, one-for-one with the phone's (jam/JamModels.kt), so a PC and
 * phones can be in the same party. The server speaks camelCase, so nothing here is renamed.
 */

/**
 * A song as the party knows it: which track and where the playhead is. How each device gets
 * the audio stays its own business, so this stays small.
 */
@Serializable
data class PartyTrack(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    /** Keeps the queue's shared manual/AutoPlay boundary. */
    val fromAutoplay: Boolean = false,
)

/** One device in the party, as every other device sees it. */
@Serializable
data class PartyMember(
    val memberId: String,
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    /** Whether they are holding a socket right now, not whether they are still in. */
    val connected: Boolean = false,
    val joinedAtMs: Long = 0,
    val lastSeenMs: Long = 0,
)

/**
 * Where the party is, as of a server timestamp: [positionMs] is true at [anchorMs], and the time
 * since is added while playing (see [PartySync.positionAt]). A late frame carries an older anchor
 * and still lands every device in the same place.
 */
@Serializable
data class PartyPlayback(
    /** Bumped on every change; a state whose seq isn't newer is a duplicate or a straggler. */
    val seq: Long = 0,
    val track: PartyTrack? = null,
    /** Bumped only when the queue's contents change; the queue itself travels as [PartyQueue]. */
    val queueSeq: Long = 0,
    val queueLength: Int = 0,
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorMs: Long = 0,
    val effectivePositionMs: Long = 0,
    val updatedBy: String? = null,
    val startedBy: String? = null,
    val startedByName: String? = null,
    val autoplayEnabled: Boolean = false,
    val updatedAtMs: Long = 0,
)

/** The party's running order, sent whole on joining and then only when it changes. */
@Serializable
data class PartyQueue(
    val seq: Long = 0,
    val index: Int = -1,
    val items: List<PartyTrack> = emptyList(),
)

@Serializable
data class PartySnapshot(
    val code: String = "",
    val createdAtMs: Long = 0,
    val maxMembers: Int = 5,
    /** False on servers that predate it, which is the free-for-all the feature shipped as. */
    val hostOnlyControl: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val serverMs: Long = 0,
)

/** The answer to a create or a join: the code, and this device's key to it. */
@Serializable
data class PartyMembership(
    val code: String,
    val token: String,
    val you: PartyMember,
    val party: PartySnapshot,
    val serverMs: Long = 0,
)

@Serializable
data class JoinRequest(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val maxMembers: Int? = null,
    val autoplayEnabled: Boolean? = null,
)

@Serializable
data class ApiError(
    @SerialName("error") val code: String = "",
    val message: String = "",
)
