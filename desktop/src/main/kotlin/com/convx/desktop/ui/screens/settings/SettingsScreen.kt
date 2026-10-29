/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.shapes.ContinuousCapsule
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.TrackTopBar
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** A root category of Settings, as on the phone (SettingsHubs). */
@Stable
class SettingsHub(val id: String, val title: String, val icon: String, val tint: Color)

/** A page under a category, opened from one of its rows. */
enum class SettingsPage(val title: String, val hub: String) {
    Spotify("Spotify", "accounts"),
    Discord("Discord", "accounts"),
    LiquidGlass("Liquid Glass", "appearance"),
    Equalizer("Convx Equalizer", "playback"),
    QualitySound("Quality & sound", "playback"),
    Transitions("Song transitions", "playback"),
    Queue("Queue & autoplay", "playback"),
    Output("Sound output", "playback"),
    YouTubeMusic("YouTube Music", "accounts"),
    LastFm("Last.fm", "accounts"),
    ListenBrainz("ListenBrainz", "accounts"),
    Network("Network & proxy", "accounts"),
    LocalFolders("Music folders", "library"),
}

object SettingsHubs {
    val player = SettingsHub("player", "Player & Lyrics", "play", SystemColors.Pink)
    val playback = SettingsHub("playback", "Playback & Audio", "equalizer", SystemColors.Red)
    val appearance = SettingsHub("appearance", "Appearance", "palette", SystemColors.Purple)
    val library = SettingsHub("library", "Library & Content", "music_note", SystemColors.Orange)
    val accounts = SettingsHub("accounts", "Accounts & Services", "account", SystemColors.Green)
    val privacy = SettingsHub("privacy", "Privacy & Storage", "security", SystemColors.Blue)
    val help = SettingsHub("help", "Help & About", "info", SystemColors.Gray)

    val all = listOf(player, playback, appearance, library, accounts, privacy, help)

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: player
}

/** Where Settings is: the category on the right, and the pages pushed over it. Kept across visits, like the phone's. */
object SettingsNav {
    var hub by mutableStateOf(SettingsHubs.player.id)
    val pages = mutableStateListOf<SettingsPage>()

    /** On a narrow window the root list is a page of its own; this is whether a category is open over it. */
    var hubOpen by mutableStateOf(false)

    /** Which way the last move went, for the slide. */
    internal var forward = true

    fun openHub(id: String) {
        forward = true
        hub = id
        pages.clear()
        hubOpen = true
    }

    fun push(page: SettingsPage) {
        forward = true
        if (hub != page.hub) {
            hub = page.hub
            pages.clear()
        }
        pages += page
        hubOpen = true
    }

    fun back() {
        forward = false
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else hubOpen = false
    }
}

/** Wider than this, the root becomes a sidebar with the chosen category beside it (the phone's tablet layout). */
private val SplitWidth = 820.dp
private val SidebarWidth = 360.dp

@Composable
fun SettingsScreen() {
    val insets = LocalPageInsets.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val split = maxWidth - insets.start >= SplitWidth
        val rootState = rememberLazyListState()
        if (split) {
            Row(Modifier.fillMaxSize().padding(start = insets.start)) {
                Box(Modifier.width(SidebarWidth).fillMaxHeight()) {
                    RootList(rootState, sidebar = true)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DetailPane(sidebar = true)
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(start = insets.start)) {
                if (SettingsNav.hubOpen) {
                    DetailPane(sidebar = false)
                } else {
                    TrackTopBar(rootState, Color.Black)
                    RootList(rootState, sidebar = false)
                }
            }
        }
    }
}

/** A scrolling column of settings under the top bar, with the window's scrollbar. */
@Composable
private fun SettingsList(state: LazyListState, horizontal: Dp, content: LazyListScope.() -> Unit) {
    val insets = LocalPageInsets.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = insets.top + 8.dp, bottom = insets.bottom + 48.dp),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = insets.top, bottom = insets.bottom + 8.dp, end = 2.dp),
        )
    }
}

/** The phone's root: the profile card, search, and the categories in their four groups. */
@Composable
private fun RootList(state: LazyListState, sidebar: Boolean) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val navigator = LocalNavigator.current
    val rootGroups = listOf(
        listOf(SettingsHubs.player, SettingsHubs.playback, SettingsHubs.appearance),
        listOf(SettingsHubs.library, SettingsHubs.accounts),
        listOf(SettingsHubs.privacy),
        listOf(SettingsHubs.help),
    )
    SettingsList(state, horizontal = 16.dp) {
        item("title") {
            Text("Settings", style = ConvxType.LargeTitle, color = ConvxColors.Label, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp))
        }
        item("search") { SearchField(query, { query = it }) }
        if (query.isNotBlank()) {
            item("results") {
                Spacer(Modifier.height(20.dp))
                SearchResults(query)
            }
            return@SettingsList
        }
        item("profile") {
            Spacer(Modifier.height(20.dp))
            ProfileCard { SettingsNav.openHub(SettingsHubs.accounts.id) }
        }
        rootGroups.forEachIndexed { i, hubs ->
            item("group$i") {
                Spacer(Modifier.height(if (i == 0) 20.dp else 28.dp))
                SettingsSection {
                    hubs.forEachIndexed { j, hub ->
                        if (j > 0) RowDivider()
                        LinkRow(
                            hub.title,
                            icon = hub.icon,
                            tint = hub.tint,
                            selected = sidebar && SettingsNav.hub == hub.id,
                        ) { SettingsNav.openHub(hub.id) }
                    }
                    if (i == 2) {
                        RowDivider()
                        LinkRow("History", icon = "music_history", tint = SystemColors.Cyan) { navigator.navigate(Screen.History) }
                    }
                }
            }
        }
    }
}

/** The account at the top of the phone's Settings; here, the accounts this PC has linked. */
@Composable
private fun ProfileCard(onClick: () -> Unit) {
    val spotify by SpotifyAccount.state.collectAsState()
    val youtube by com.convx.desktop.integrations.youtube.YouTubeAccount.state.collectAsState()
    // The phone's card is the YouTube Music account; Spotify's stands in until you sign in there.
    val youtubeProfile = (youtube as? com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedIn)?.profile
    val spotifyProfile = (spotify as? SpotifyAccount.State.SignedIn)?.profile
    val name = youtubeProfile?.name ?: spotifyProfile?.name
    val photo = youtubeProfile?.thumbnail ?: spotifyProfile?.image
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(20.dp))
            .background(ConvxColors.Card)
            .rowPress(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            Artwork(photo, Modifier.size(60.dp), size = 160, shape = CircleShape, placeholderIcon = "person")
        } else {
            Box(Modifier.size(60.dp).clip(CircleShape).background(ConvxColors.CardTertiary), contentAlignment = Alignment.Center) {
                ConvxIcon("person", tint = Color.White.copy(alpha = 0.85f), size = 30.dp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name ?: "Sign in", style = ConvxType.Title3, color = ConvxColors.Label, maxLines = 1)
            Text(
                youtubeProfile?.let { it.email ?: it.handle } ?: "YouTube Music, Spotify and more",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                maxLines = 1,
            )
        }
        ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp)
    }
}

/** The phone's search pill, at the top here where a mouse expects it. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(ContinuousCapsule).background(ConvxColors.Card).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("search", tint = ConvxColors.SecondaryLabel, size = 17.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = ConvxType.Body.copy(color = ConvxColors.Label),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(22.dp).clip(CircleShape).rowPress({ onValueChange("") }), contentAlignment = Alignment.Center) {
                ConvxIcon("close", tint = ConvxColors.SecondaryLabel, size = 14.dp)
            }
        }
    }
}

/** One thing Settings can find: where it lives, and the words people look for it by. */
private class SearchEntry(
    val title: String,
    val subtitle: String?,
    val hub: SettingsHub,
    val page: SettingsPage? = null,
    val keywords: String = "",
)

private val searchEntries: List<SearchEntry> by lazy {
    val h = SettingsHubs
    listOf(
        SearchEntry("Wavy progress bar", "The squiggly line while music plays", h.player, keywords = "seek bar slider squiggly"),
        SearchEntry("Word-by-word lyrics", "Light up each word as it's sung", h.player, keywords = "karaoke synced lyrics"),
        SearchEntry("Equalizer", "Shape the sound", h.playback, SettingsPage.Equalizer, "eq bass treble mids preset parametric autoeq dolby dirac"),
        SearchEntry("Spatial audio", "Wider stereo for headphones", h.playback, SettingsPage.Equalizer, "3d surround headphones widen"),
        SearchEntry("Audio quality", "High or low", h.playback, SettingsPage.QualitySound, "bitrate stream data"),
        SearchEntry("Audio normalization", "Even out loud and quiet songs", h.playback, SettingsPage.QualitySound, "loudness volume level"),
        SearchEntry("Skip silence", "Fast forward through silent parts", h.playback, SettingsPage.QualitySound, "silence gap"),
        SearchEntry("Song transitions", "Crossfade between songs", h.playback, SettingsPage.Transitions, "crossfade fade automix gapless"),
        SearchEntry("Persistent queue", "Restore your last queue", h.playback, SettingsPage.Queue, "queue restore"),
        SearchEntry("Enable similar content", "Keep going when the queue ends", h.playback, SettingsPage.Queue, "radio autoplay queue"),
        SearchEntry("Remember shuffle and repeat", null, h.playback, SettingsPage.Queue, "shuffle repeat"),
        SearchEntry("YouTube Music", "Log in, channels and sync", h.accounts, SettingsPage.YouTubeMusic, "youtube google login sign in account channel sync token"),
        SearchEntry("Login with token", null, h.accounts, SettingsPage.YouTubeMusic, "token cookie"),
        SearchEntry("More content", "Recommendations from your account", h.accounts, SettingsPage.YouTubeMusic, "login browse personalized"),
        SearchEntry("Auto sync with account", null, h.accounts, SettingsPage.YouTubeMusic, "sync likes playlists"),
        SearchEntry("Import from Spotify", "Copy playlists and Liked Songs", h.accounts, SettingsPage.Spotify, "spotify import playlists"),
        SearchEntry("Last.fm", "Scrobbling", h.accounts, SettingsPage.LastFm, "lastfm scrobble love now playing"),
        SearchEntry("ListenBrainz", "Scrobbling with a token", h.accounts, SettingsPage.ListenBrainz, "listenbrainz scrobble musicbrainz"),
        SearchEntry("Music folders", "Folders with music files", h.library, SettingsPage.LocalFolders, "local files folders scan mp3 flac"),
        SearchEntry("Scan local files", null, h.library, SettingsPage.LocalFolders, "scan local files"),
        SearchEntry("Network storage", "SMB and WebDAV shares", h.library, SettingsPage.LocalFolders, "smb webdav nas network share"),
        SearchEntry("Excluded folders", null, h.library, SettingsPage.LocalFolders, "exclude skip folder"),
        SearchEntry("Network & proxy", "IP version and proxy", h.accounts, SettingsPage.Network, "proxy ipv6 ipv4 network socks http"),
        SearchEntry("Apple Music import", "Import playlists from a link or export", h.accounts, keywords = "apple music playlist import export itunes xml"),
        SearchEntry("Sound output", "Speakers or headphones", h.playback, SettingsPage.Output, "device speaker headphones output"),
        SearchEntry("Liquid Glass", "Blur, lens and highlights", h.appearance, SettingsPage.LiquidGlass, "glass blur lens refraction vibrancy chromatic aberration rim"),
        SearchEntry("Content country", "Charts and recommendations", h.library, keywords = "region location"),
        SearchEntry("Content language", "The language YouTube Music answers in", h.library, keywords = "locale"),
        SearchEntry("Hide explicit content", null, h.library, keywords = "explicit filter"),
        SearchEntry("Hide music videos", "Only audio tracks", h.library, keywords = "video filter"),
        SearchEntry("Spotify", "Your playlists and Liked Songs", h.accounts, SettingsPage.Spotify, "account login import"),
        SearchEntry("Discord", "Listening status on your profile", h.accounts, SettingsPage.Discord, "rich presence rpc status buttons"),
        SearchEntry("Song cache", "Songs kept for instant starts", h.privacy, keywords = "storage clear cache"),
        SearchEntry("Image cache", "Covers and artist photos", h.privacy, keywords = "storage clear cache"),
        SearchEntry("App data", "Where Convx keeps your library", h.privacy, keywords = "folder backup"),
        SearchEntry("About", "Version and licence", h.help, keywords = "version license gpl"),
    )
}

@Composable
private fun SearchResults(query: String) {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val hits = searchEntries.filter { e ->
        val text = listOfNotNull(e.title, e.subtitle, e.keywords, e.hub.title, e.page?.title).joinToString(" ").lowercase()
        terms.isNotEmpty() && terms.all { it in text }
    } + SettingsHubs.all.filter { hub -> terms.isNotEmpty() && terms.all { it in hub.title.lowercase() } }
        .map { SearchEntry(it.title, null, it) }
    if (hits.isEmpty()) {
        Text(
            "No settings found for “${query.trim()}”",
            style = ConvxType.Body,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        )
        return
    }
    SettingsSection(title = if (hits.size == 1) "1 result" else "${hits.size} results") {
        hits.forEachIndexed { i, hit ->
            if (i > 0) RowDivider()
            LinkRow(
                hit.title,
                subtitle = hit.hub.title + (hit.subtitle?.let { " · $it" } ?: ""),
                icon = hit.hub.icon,
                tint = hit.hub.tint,
            ) {
                if (hit.page != null) SettingsNav.push(hit.page) else SettingsNav.openHub(hit.hub.id)
            }
        }
    }
}

/** The right side: the category, or the page pushed over it, sliding in and out as iOS pushes. */
@Composable
private fun DetailPane(sidebar: Boolean) {
    val key = SettingsNav.hub + "/" + SettingsNav.pages.joinToString("/") { it.name }
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            val sameHub = initialState.substringBefore('/') == targetState.substringBefore('/')
            when {
                !sameHub -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                SettingsNav.forward -> push()
                else -> pop()
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        val hub = SettingsHubs.byId(state.substringBefore('/'))
        val page = state.substringAfter('/', "").split('/').lastOrNull { it.isNotEmpty() }?.let { SettingsPage.valueOf(it) }
        val listState = rememberLazyListState()
        if (sidebar) TrackTopBar(listState, Color.Black)
        val backLabel = when {
            page != null -> SettingsNav.pages.getOrNull(SettingsNav.pages.size - 2)?.title ?: hub.title
            !sidebar -> "Settings"
            else -> null
        }
        SettingsList(listState, horizontal = if (sidebar) 28.dp else 16.dp) {
            item("header") {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (backLabel != null && (page != null || !sidebar)) {
                        BackButton(backLabel) { SettingsNav.back() }
                    }
                    Text(
                        page?.title ?: hub.title,
                        style = ConvxType.LargeTitle,
                        color = ConvxColors.Label,
                        modifier = Modifier.padding(start = 4.dp, top = if (backLabel != null && (page != null || !sidebar)) 14.dp else 4.dp, bottom = 20.dp),
                    )
                }
            }
            item("body") {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (page != null) PageContent(page) else HubContent(hub)
                }
            }
        }
    }
}

private fun push(): ContentTransform =
    (slideInHorizontally(tween(320)) { it } + fadeIn(tween(160))) togetherWith
        (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(200)))

private fun pop(): ContentTransform =
    (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(200))) togetherWith
        (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(160)))

/** The phone's back button: a drop of glass with a chevron, naming where it goes on hover. */
@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    GlassCircleButton(onClick, Modifier.padding(start = 2.dp, top = 4.dp), size = 40.dp, tooltip = label) {
        ConvxIcon("chevron_leftpx", size = 20.dp)
    }
}

@Composable
private fun HubContent(hub: SettingsHub) {
    when (hub.id) {
        "player" -> PlayerHub()
        "playback" -> PlaybackHub()
        "appearance" -> AppearanceHub()
        "library" -> LibraryHub()
        "accounts" -> AccountsHub()
        "privacy" -> PrivacyHub()
        else -> HelpHub()
    }
}

@Composable
private fun PageContent(page: SettingsPage) {
    when (page) {
        SettingsPage.Spotify -> SpotifySettings()
        SettingsPage.Discord -> DiscordSettings()
        SettingsPage.LiquidGlass -> LiquidGlassSettings()
        SettingsPage.Equalizer -> EqualizerPage()
        SettingsPage.QualitySound -> QualitySoundSettings()
        SettingsPage.Transitions -> SongTransitionsSettings()
        SettingsPage.Queue -> QueueSettings()
        SettingsPage.Output -> OutputSettings()
        SettingsPage.YouTubeMusic -> YouTubeAccountSettings()
        SettingsPage.LastFm -> LastFmSettings()
        SettingsPage.ListenBrainz -> ListenBrainzSettings()
        SettingsPage.Network -> NetworkSettings()
        SettingsPage.LocalFolders -> LocalFoldersSettings()
    }
}

/** The gap between two sections of a page. */
@Composable
fun SectionGap() = Spacer(Modifier.height(28.dp))
