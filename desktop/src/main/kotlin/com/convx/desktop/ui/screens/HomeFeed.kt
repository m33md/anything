/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AlbumRef
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.convx.desktop.data.SpeedDialEntry
import com.convx.desktop.data.toItem
import com.convx.desktop.data.toSong
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.RelatedPage
import com.music.innertube.utils.completed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Home's sections by the phone's ids, which are what Arrange home feed saves. */
sealed class HomeSection(val id: String, val baseWeight: Int) {
    data object Hero : HomeSection("hero", 110)
    data object SpeedDial : HomeSection("speed_dial", 100)
    data object QuickPicks : HomeSection("quick_picks", 90)
    data object DailyDiscover : HomeSection("daily_discover", 80)
    data object ListenAgain : HomeSection("keep_listening", 50)
    data object TopSongs : HomeSection("top_songs", 55)
    data object AccountPlaylists : HomeSection("account_playlists", 40)
    data object ForgottenFavorites : HomeSection("forgotten_favorites", 30)
    data object FromTheCommunity : HomeSection("from_the_community", 20)
    data class SimilarRecommendation(val index: Int) : HomeSection("similar_recommendation_$index", 10)
    data class HomePageSection(val index: Int) : HomeSection("home_page_section_$index", 10)
    data object MoodAndGenres : HomeSection("mood_and_genres", 5)

    companion object {
        /** The sections Arrange home feed lists, in the order Home shows them by default, with the phone's names. */
        val arrangeable = listOf(
            "hero" to "Featured card",
            "keep_listening" to "Keep listening",
            "quick_picks" to "Quick picks",
            "speed_dial" to "Speed dial",
            "from_the_community" to "From the community",
            "daily_discover" to "Daily discover",
            "account_playlists" to "Your playlists",
            "forgotten_favorites" to "Forgotten favourites",
            "mood_and_genres" to "Mood and Genres",
        )
    }
}

/** What a "Similar to" shelf is similar to: one of your most played artists, songs or albums. */
sealed interface SimilarSeed {
    val title: String
    val thumbnail: String?

    data class OfArtist(val id: String, override val title: String, override val thumbnail: String?) : SimilarSeed
    data class OfSong(val song: Song) : SimilarSeed {
        override val title get() = song.title
        override val thumbnail get() = song.thumbnail
    }
    data class OfAlbum(val album: AlbumRef, override val thumbnail: String?) : SimilarSeed {
        override val title get() = album.name
    }
}

class SimilarRecommendation(val seed: SimilarSeed, val items: List<YTItem>)

/** Everything Home shows, as the phone's HomeViewModel gathers it. Null is still loading. */
data class HomeFeedState(
    val quickPicks: List<Song>? = null,
    val forgottenFavorites: List<Song>? = null,
    val topSongs: List<Song>? = null,
    /** Your recent songs and albums; they fill Speed dial up, as on the phone. */
    val keepListening: List<YTItem> = emptyList(),
    val dailyDiscover: List<SongItem>? = null,
    val communityPlaylists: List<PlaylistItem>? = null,
    val similar: List<SimilarRecommendation>? = null,
    val accountPlaylists: List<PlaylistItem>? = null,
    val listenAgain: HomePage.Section? = null,
    val homePage: HomePage? = null,
    val homeError: Throwable? = null,
    val loading: Boolean = false,
    /** Seeds the shuffled section order; new on each refresh, as on the phone. */
    val seed: Long = System.currentTimeMillis(),
)

/**
 * The phone's HomeViewModel for the desktop: Quick picks, Daily discover, From the community,
 * Similar to, Forgotten favourites and Most played from your listening history, and YouTube
 * Music's own home on top. The phone keeps songs related to each one you play in its database;
 * here they're asked of YouTube when Home loads, from the same seeds its query picks.
 */
object HomeFeed {
    private const val Tag = "HomeFeed"
    private const val Day = 86_400_000L
    private const val NewReleasesBrowseId = "FEmusic_new_releases"
    private val ExcludedCommunityAuthors = setOf("YouTube Music", "YouTube", "Playlist")

    private val _state = MutableStateFlow(HomeFeedState())
    val state: StateFlow<HomeFeedState> = _state.asStateFlow()

    private var job: Job? = null

    // Background recommendations go three at a time, as on the phone.
    private val requests = Semaphore(3)
    private val related = ConcurrentHashMap<String, RelatedPage>()

    /** Loads Home once a session, or again when [force]d (the phone's pull to refresh). */
    fun load(force: Boolean = false) {
        if (!force && (job != null)) return
        job?.cancel()
        if (force) related.clear()
        _state.update { it.copy(loading = true, homeError = null, seed = if (force) System.currentTimeMillis() else it.seed) }
        job = AppScope.launch(Dispatchers.IO) {
            try {
                loadLocal()
                withTimeoutOrNull(45_000) {
                    coroutineScope {
                        launch { loadQuickPicks() }
                        launch { loadHome() }
                        launch { loadDailyDiscover() }
                        launch { loadCommunityPlaylists() }
                        launch { loadSimilar() }
                        if (YouTubeAccount.state.value is YouTubeAccount.State.SignedIn) launch { loadAccountPlaylists() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(Tag, "Home didn't load", e)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Your own songs Home may show: no local files, and no videos when they're hidden. */
    private fun List<Song>.playable(settings: Settings) =
        filter { (!settings.hideVideos || !it.isVideo) && !com.convx.desktop.player.LocalMusic.isLocal(it.id) }

    /** Songs, albums and artists from your own history: instant, no network. */
    private fun loadLocal() {
        val settings = AppSettings.value
        val now = System.currentTimeMillis()
        val twoWeeks = now - 14 * Day
        val songs = Library.mostPlayedSongs(twoWeeks, limit = 15, offset = 5).playable(settings).shuffled().take(10)
        val albums = Library.mostPlayedAlbums(twoWeeks, limit = 8).drop(2)
            .filter { (_, song) -> song.thumbnail != null }
            .shuffled().take(5)
            .map { (album, song) -> AlbumItem(browseId = album.id, playlistId = "", title = album.name, artists = song.artists.map { Artist(it.name, it.id) }, thumbnail = song.thumbnail!!) }
        val followed = Library.value.artists.associateBy { it.id }
        val artists = Library.mostPlayedArtists(twoWeeks, limit = 10)
            .mapNotNull { ref -> followed[ref.id]?.takeIf { it.thumbnail != null } }
            .shuffled().take(5)
            .map { ArtistItem(id = it.id, title = it.name, thumbnail = it.thumbnail, shuffleEndpoint = null, radioEndpoint = null) }
        _state.update {
            it.copy(
                forgottenFavorites = Library.forgottenFavorites(now).playable(settings).shuffled().take(20),
                topSongs = Library.mostPlayedSongs(twoWeeks, limit = 10).playable(settings),
                keepListening = (songs.map { song -> song.toItem() } + albums + artists).shuffled(),
            )
        }
    }

    private suspend fun relatedOf(videoId: String): RelatedPage? {
        related[videoId]?.let { return it }
        return requests.withPermit {
            val endpoint = YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()?.relatedEndpoint ?: return@withPermit null
            YouTube.related(endpoint).getOrNull()
        }?.also { related[videoId] = it }
    }

    /**
     * The phone's Quick picks query: songs related to your last five plays, this week's five most
     * played and your ten all-time favourites, most often related first, with eight forgotten
     * favourites mixed in, twenty of them shuffled.
     */
    private suspend fun loadQuickPicks() {
        val settings = AppSettings.value
        val history = Library.value.history
        if (history.isEmpty()) {
            _state.update { it.copy(quickPicks = emptyList()) }
            return
        }
        val seeds = (
            history.asSequence().map { it.song }.take(5).toList() +
                Library.mostPlayedSongs(System.currentTimeMillis() - 7 * Day, limit = 5) +
                Library.mostPlayedSongs(0, limit = 10)
            ).filterNot { com.convx.desktop.player.LocalMusic.isLocal(it.id) }
            .distinctBy { it.id }
            .take(12)
        val pages = coroutineScope { seeds.map { seed -> async { relatedOf(seed.id) } }.awaitAll() }
        val referred = pages.filterNotNull()
            .flatMap { page -> page.songs.filtered(settings) }
            .groupBy { it.id }
            .values
            .sortedByDescending { it.size }
            .take(100)
            .map { it.first().toSong() }
        val forgotten = _state.value.forgottenFavorites.orEmpty().take(8)
        _state.update { it.copy(quickPicks = (referred + forgotten).distinctBy { song -> song.id }.shuffled().take(20)) }
    }

    /** One recommendation for each of five of your liked songs. */
    private suspend fun loadDailyDiscover() {
        val settings = AppSettings.value
        val liked = Library.value.liked.map { it.song }.filterNot { com.convx.desktop.player.LocalMusic.isLocal(it.id) }
        if (liked.isEmpty()) {
            _state.update { it.copy(dailyDiscover = emptyList()) }
            return
        }
        val picks = coroutineScope {
            liked.shuffled().take(5).map { seed ->
                async {
                    relatedOf(seed.id)?.songs
                        ?.filter { !it.explicit && (!settings.hideVideos || !it.isVideoSong) }
                        ?.shuffled()
                        ?.firstOrNull { it.id != seed.id }
                }
            }.awaitAll()
        }
        _state.update { it.copy(dailyDiscover = picks.filterNotNull().distinctBy { song -> song.id }.shuffled()) }
    }

    private fun isCommunityPlaylist(playlist: PlaylistItem) =
        playlist.author?.name !in ExcludedCommunityAuthors && !playlist.id.startsWith("RD") && !playlist.id.startsWith("OLAK")

    /** Other people's playlists from your most played artists' pages and your songs' related pages. */
    private suspend fun loadCommunityPlaylists() {
        val fourWeeks = System.currentTimeMillis() - 28 * Day
        val artistSeeds = Library.mostPlayedArtists(fourWeeks, limit = 10).filter { it.id!!.startsWith("UC") }.shuffled().take(3)
        val songSeeds = Library.mostPlayedSongs(fourWeeks, limit = 5)
            .filterNot { com.convx.desktop.player.LocalMusic.isLocal(it.id) }
            .shuffled().take(2)
        val candidates = coroutineScope {
            val fromArtists = artistSeeds.map { seed ->
                async {
                    requests.withPermit { YouTube.artist(seed.id!!).getOrNull() }?.sections.orEmpty()
                        .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                        .filter { isCommunityPlaylist(it) && it.author?.name != seed.name }
                }
            }
            val fromSongs = songSeeds.map { seed -> async { relatedOf(seed.id)?.playlists.orEmpty().filter { isCommunityPlaylist(it) } } }
            (fromArtists + fromSongs).awaitAll().flatten()
        }.distinctBy { it.id }.shuffled().take(5)
        // Kept only if they have songs, with the count the playlist's own page gives.
        val playlists = coroutineScope {
            candidates.map { playlist ->
                async {
                    val page = requests.withPermit { YouTube.playlist(playlist.id.removePrefix("VL")).getOrNull() } ?: return@async null
                    if (page.songs.isEmpty()) null else playlist.copy(songCountText = page.playlist.songCountText ?: playlist.songCountText)
                }
            }.awaitAll()
        }
        _state.update { it.copy(communityPlaylists = playlists.filterNotNull().shuffled()) }
    }

    /** "Similar to" four of your artists, three of your songs and two of your albums. */
    private suspend fun loadSimilar() {
        val settings = AppSettings.value
        val twoWeeks = System.currentTimeMillis() - 14 * Day
        fun List<YTItem>.recommendations() = distinctBy { it.id }.filtered(settings).shuffled()
        val results = coroutineScope {
            val artists = Library.mostPlayedArtists(twoWeeks, limit = 15).filter { it.id!!.startsWith("UC") }.shuffled().take(4).map { ref ->
                async {
                    val page = requests.withPermit { YouTube.artist(ref.id!!).getOrNull() } ?: return@async null
                    val items = page.sections.takeLast(3).flatMap { it.items }.recommendations().take(12)
                    if (items.isEmpty()) null else SimilarRecommendation(SimilarSeed.OfArtist(ref.id!!, page.artist.title, page.artist.thumbnail), items)
                }
            }
            val songs = Library.mostPlayedSongs(twoWeeks, limit = 15)
                .filter { it.album != null && !com.convx.desktop.player.LocalMusic.isLocal(it.id) }
                .shuffled().take(3).map { song ->
                    async {
                        val page = relatedOf(song.id) ?: return@async null
                        val items = (page.songs.shuffled().take(10) + page.albums.shuffled().take(5) + page.artists.shuffled().take(3) + page.playlists.shuffled().take(3))
                            .recommendations()
                        if (items.isEmpty()) null else SimilarRecommendation(SimilarSeed.OfSong(song), items)
                    }
                }
            val albums = Library.mostPlayedAlbums(twoWeeks, limit = 10).filter { (_, song) -> song.thumbnail != null }.shuffled().take(2).map { (album, song) ->
                async {
                    val items = mutableListOf<YTItem>()
                    requests.withPermit { YouTube.album(album.id).getOrNull() }?.let { items += it.otherVersions }
                    song.artists.firstOrNull { it.id != null }?.id?.let { artistId ->
                        requests.withPermit { YouTube.artist(artistId).getOrNull() }?.sections?.lastOrNull()?.let { items += it.items }
                    }
                    val picks = items.recommendations().take(10)
                    if (picks.isEmpty()) null else SimilarRecommendation(SimilarSeed.OfAlbum(album, song.thumbnail), picks)
                }
            }
            (artists + songs + albums).awaitAll()
        }
        _state.update { it.copy(similar = results.filterNotNull().shuffled()) }
    }

    /** YouTube Music's home and new releases, without repeats, with Listen again taken out to place on its own. */
    private suspend fun loadHome() {
        val settings = AppSettings.value
        coroutineScope {
            val newReleases = async { YouTube.home(browseId = NewReleasesBrowseId).getOrNull()?.sections.orEmpty() }
            YouTube.home().onSuccess { page ->
                val seen = mutableSetOf<String>()
                val sections = (page.sections + newReleases.await()).mapNotNull { section ->
                    if (!seen.add(section.title.lowercase())) return@mapNotNull null
                    val items = section.items.filter { item ->
                        (!settings.hideExplicit || !item.explicit) &&
                            (!settings.hideVideos || item !is SongItem || !item.isVideoSong)
                    }
                    if (items.isEmpty()) null else section.copy(items = items)
                }
                val listenAgain = sections.firstOrNull { it.isListenAgain() }
                _state.update { it.copy(listenAgain = listenAgain, homePage = page.copy(sections = sections - listOfNotNull(listenAgain).toSet()), homeError = null) }
            }.onFailure { error ->
                newReleases.cancel()
                Log.w(Tag, "YouTube Music's home didn't load", error)
                _state.update { it.copy(homeError = error) }
            }
        }
    }

    private suspend fun loadAccountPlaylists() {
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess { page ->
            _state.update { state ->
                state.copy(
                    accountPlaylists = page.items.filterIsInstance<PlaylistItem>()
                        .filterNot { it.id == "SE" }
                        .filterNot { AppSettings.value.hideVideos && it.id.startsWith("SS") },
                )
            }
        }.onFailure { Log.w(Tag, "Your YouTube playlists didn't load", it) }
    }

    /** The phone's Surprise me: four times in five one of your own songs, else anything on Home. */
    fun randomItem(state: HomeFeedState): YTItem? {
        val yours = (state.quickPicks.orEmpty().map { it.toItem() } + state.keepListening.filterIsInstance<SongItem>()).distinctBy { it.id }
        val others = (
            state.keepListening.filterIsInstance<AlbumItem>() +
                state.similar.orEmpty().flatMap { it.items } +
                state.homePage?.sections.orEmpty().flatMap { it.items } +
                state.listenAgain?.items.orEmpty()
            ).distinctBy { it.id }
        val pick = if (yours.isNotEmpty() && (others.isEmpty() || Random.nextFloat() < 0.8f)) yours.randomOrNull() else others.randomOrNull()
        return pick ?: yours.firstOrNull() ?: others.firstOrNull()
    }

    /** Speed dial: your pins, filled up to 27 from Keep listening and then Quick picks, as on the phone. */
    fun speedDial(state: HomeFeedState, pins: List<SpeedDialEntry>): List<YTItem> {
        val filled = pins.map { it.toItem() }.toMutableList()
        fun fill(items: List<YTItem>) {
            if (filled.size >= 27) return
            filled += items.filter { item -> filled.none { it.id == item.id } }.take(27 - filled.size)
        }
        fill(state.keepListening.filter { it is SongItem || it is AlbumItem })
        fill(state.quickPicks.orEmpty().map { it.toItem() })
        return filled.take(27)
    }
}

/** Listen again keeps its own place on Home, whichever name YouTube sends it under. */
fun HomePage.Section.isListenAgain(): Boolean {
    val name = title.trim()
    return name.equals("Listen again", ignoreCase = true) || name.equals("Keep listening", ignoreCase = true)
}

/**
 * The phone's buildHomeSections: what's there to show, less what you've hidden, in the default
 * order, the order you arranged, or shuffled by [seed] with the featured card kept on top.
 */
fun buildHomeSections(state: HomeFeedState, settings: Settings, pinsShown: Boolean, hasMoods: Boolean): List<HomeSection> {
    val list = mutableListOf<HomeSection>()
    if (settings.homeHeroCard && !state.quickPicks.isNullOrEmpty()) list += HomeSection.Hero
    if (pinsShown) list += HomeSection.SpeedDial
    if (!state.quickPicks.isNullOrEmpty()) list += HomeSection.QuickPicks
    if (!state.communityPlaylists.isNullOrEmpty()) list += HomeSection.FromTheCommunity
    if (!state.dailyDiscover.isNullOrEmpty()) list += HomeSection.DailyDiscover
    if (state.listenAgain?.items?.isNotEmpty() == true) list += HomeSection.ListenAgain
    if (!state.topSongs.isNullOrEmpty()) list += HomeSection.TopSongs
    if (!state.accountPlaylists.isNullOrEmpty()) list += HomeSection.AccountPlaylists
    if (!state.forgottenFavorites.isNullOrEmpty()) list += HomeSection.ForgottenFavorites
    // Capped, as the phone caps them: past four "Similar to" and six of YouTube's shelves Home drags.
    state.similar?.indices?.take(4)?.forEach { list += HomeSection.SimilarRecommendation(it) }
    state.homePage?.sections?.indices?.take(6)?.forEach { list += HomeSection.HomePageSection(it) }
    if (hasMoods) list += HomeSection.MoodAndGenres

    list.removeAll { it.id in settings.homeSectionsHidden }

    return when {
        settings.homeRandomOrder -> list.sortedByDescending { randomWeight(it, state.seed) }
        settings.homeSectionOrder.isNotEmpty() -> {
            val rank = settings.homeSectionOrder.distinct().withIndex().associate { (i, id) -> id to i }
            list.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { -it.baseWeight }))
        }
        else -> list.sortedByDescending(::defaultWeight)
    }
}

private fun defaultWeight(section: HomeSection): Int = when (section) {
    is HomeSection.SimilarRecommendation -> 30 - section.index
    is HomeSection.HomePageSection -> 20 - section.index
    HomeSection.Hero -> 110
    HomeSection.ListenAgain -> 100
    HomeSection.QuickPicks -> 90
    HomeSection.SpeedDial -> 80
    HomeSection.FromTheCommunity -> 70
    HomeSection.DailyDiscover -> 60
    HomeSection.AccountPlaylists -> 50
    HomeSection.ForgottenFavorites -> 40
    HomeSection.MoodAndGenres -> 10
    // The phone gives Most played no default place, so it settles at the bottom.
    HomeSection.TopSongs -> 0
}

/** The phone's weights: each section shuffled on its own seed, so one appearing doesn't reshuffle the rest. */
private fun randomWeight(section: HomeSection, seed: Long): Int {
    val random = Random(seed + section.id.hashCode())
    return when (section) {
        // Kept on top, even shuffled.
        HomeSection.Hero -> 10_000 + random.nextInt(-50, 50)
        HomeSection.SpeedDial, HomeSection.QuickPicks, HomeSection.DailyDiscover -> 500 + random.nextInt(-200, 400)
        HomeSection.ListenAgain, HomeSection.AccountPlaylists, HomeSection.ForgottenFavorites, HomeSection.FromTheCommunity ->
            300 + random.nextInt(-100, 400)
        else -> 100 + random.nextInt(-50, 50)
    }
}
