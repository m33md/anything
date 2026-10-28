/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Settings
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.bleedStart
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.Shelf
import com.convx.desktop.ui.components.SongShelf
import com.convx.desktop.ui.components.TopPickCard
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.pages.ChartsPage
import com.music.innertube.pages.ExplorePage
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.MoodAndGenres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Applies the hide-explicit / hide-videos settings, as the phone does to every feed. */
fun <T : YTItem> List<T>.filtered(settings: Settings): List<T> =
    filterExplicit(settings.hideExplicit).filterVideoSongs(settings.hideVideos)

/** True when a shelf is mostly songs and long enough for the four-deep list columns. */
fun List<YTItem>.isSongShelf(): Boolean {
    val songs = count { it is SongItem }
    return songs >= 8 && songs * 2 >= size
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen() {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedChip by rememberSaveable { mutableStateOf<String?>(null) }

    val home = rememberPage("home") { YouTube.home().getOrThrow() }
    val charts = rememberPage("charts") { YouTube.getChartsPage().getOrThrow() }
    val explore = rememberPage("explore") { YouTube.explore().getOrThrow() }
    val chipPage = selectedChip?.let { title ->
        val chip = home.value?.chips?.firstOrNull { it.title == title }
        rememberPage("home-chip-$title") {
            val endpoint = chip?.endpoint ?: error("This filter isn't available any more")
            YouTube.home(params = endpoint.params, browseId = endpoint.browseId).getOrThrow()
        }
    }
    var loadingMore by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    PageList(
        state = listState,
        onNearEnd = {
            val page = home.value ?: return@PageList
            val continuation = page.continuation ?: return@PageList
            if (loadingMore || selectedChip != null) return@PageList
            loadingMore = true
            scope.launch {
                val next = withContext(Dispatchers.IO) { YouTube.home(continuation).getOrNull() }
                if (next != null) home.update { it.copy(sections = it.sections + next.sections, continuation = next.continuation) }
                loadingMore = false
            }
        },
    ) {
        item(key = "greeting") {
            Text(greeting(), style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        val chips = home.value?.chips.orEmpty()
        if (chips.isNotEmpty()) {
            item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Chip("All", selectedChip == null, { selectedChip = null }) }
                    items(chips, key = { it.title }) { chip ->
                        Chip(chip.title, selectedChip == chip.title, { selectedChip = if (selectedChip == chip.title) null else chip.title })
                    }
                }
            }
        }

        if (chipPage != null) {
            val page = chipPage.value
            when {
                page != null -> homeSections(page.sections, settings, heroFirst = true)
                chipPage.error != null -> item { MessageState("Couldn't load this mood", friendlyError(chipPage.error), actionText = "Try again", onAction = { chipPage.retry() }) }
                else -> item { LoadingState() }
            }
            return@PageList
        }

        // Quick picks from your own listening, like the phone's Keep Listening.
        val recent = Library.recentSongs(16)
        if (recent.size >= 4) {
            item(key = "recent") {
                RecentGrid(recent.take(8)) { index -> PlayerController.playSongs(recent, index, "Recently played") }
            }
        }

        val chartsPage = charts.value
        val explorePage = explore.value
        val topPicks = chartsPage?.sections?.firstOrNull { it.chartType == ChartsPage.ChartType.TOP }?.items.orEmpty().filtered(settings)
        val trending = chartsPage?.sections?.firstOrNull { it.chartType == ChartsPage.ChartType.TRENDING }?.items.orEmpty().filtered(settings)
        val newReleases = explorePage?.newReleaseAlbums.orEmpty().filtered(settings)
        val heroItems = topPicks.ifEmpty { trending }.ifEmpty { newReleases }
        if (heroItems.isNotEmpty()) {
            item(key = "top_picks") {
                Shelf("Top Picks for You", spacing = 12.dp, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_charts", null, "Charts")) }) {
                    items(heroItems.distinctBy { it.id }, key = { it.id }) { TopPickCard(it, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) }
                }
            }
        }
        if (heroItems !== newReleases && newReleases.isNotEmpty()) {
            item(key = "new_releases") {
                ItemShelf("New Releases", newReleases, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_new_releases_albums", null, "New Releases")) })
                Spacer(Modifier.height(20.dp))
            }
        }
        if (topPicks.isNotEmpty() && trending.isNotEmpty()) {
            item(key = "trending") {
                TrendingShelf(trending)
                Spacer(Modifier.height(20.dp))
            }
        }

        val liked = library.liked
        if (liked.size >= 4) {
            item(key = "liked_shelf") {
                SongShelf(
                    "From your Liked Songs",
                    liked.take(16).map { it.song },
                    onTitleClick = { navigator.navigate(Screen.Liked) },
                ) { index -> PlayerController.playSongs(liked.map { it.song }, index, "Liked Songs") }
                Spacer(Modifier.height(20.dp))
            }
        }

        val homePage = home.value
        when {
            homePage != null -> homeSections(homePage.sections, settings, heroFirst = false)
            home.error != null && charts.value == null -> item {
                MessageState(
                    "Couldn't reach YouTube Music",
                    friendlyError(home.error),
                    icon = "offline",
                    actionText = "Try again",
                    onAction = {
                        home.retry()
                        charts.retry()
                        explore.retry()
                    },
                )
            }
            else -> item { LoadingState() }
        }

        val moods = explorePage?.moodAndGenres.orEmpty().distinctBy { it.title }
        if (moods.isNotEmpty()) {
            item(key = "moods") { MoodGrid("Moods & Genres", moods) }
        }
        if (loadingMore) item(key = "more") { LoadingState() }
    }
}

/** YouTube Music's own home shelves, each drawn the way the phone draws that kind of shelf. */
fun LazyListScope.homeSections(sections: List<HomePage.Section>, settings: Settings, heroFirst: Boolean) {
    sections.forEachIndexed { index, section ->
        val items = section.items.filtered(settings).distinctBy { it.id }
        if (items.isEmpty()) return@forEachIndexed
        item(key = "home_${index}_${section.title}") {
            val navigator = LocalNavigator.current
            val onTitle = section.endpoint?.let { endpoint ->
                {
                    if (endpoint.browseId == "FEmusic_moods_and_genres") navigator.navigate(Screen.Browse(endpoint.browseId, null, section.title))
                    else navigator.navigate(Screen.Browse(endpoint.browseId, endpoint.params, section.title))
                }
            }
            when {
                items.isSongShelf() -> {
                    val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
                    SongShelf(section.title, songs, subtitle = section.label, onTitleClick = onTitle) { i ->
                        PlayerController.playSongs(songs, i, section.title)
                    }
                }
                heroFirst && index == 0 -> Shelf(section.title, subtitle = section.label, onTitleClick = onTitle, spacing = 12.dp) {
                    items(items, key = { it.id }) { TopPickCard(it, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) }
                }
                else -> ItemShelf(section.title, items, subtitle = section.label, onTitleClick = onTitle)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Trending as a numbered chart list, two columns deep. */
@Composable
private fun TrendingShelf(items: List<YTItem>) {
    val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
    if (songs.size >= 4) {
        SongShelf("Trending", songs.take(20)) { i -> PlayerController.playSongs(songs, i, "Trending") }
    } else {
        ItemShelf("Trending", items)
    }
}

/** Your recent songs as Spotify's top-of-home shortcut tiles, two rows of four. */
@Composable
private fun RecentGrid(songs: List<com.convx.desktop.data.Song>, onPlay: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        songs.chunked(4).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, song ->
                    val index = row * 4 + col
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = if (hovered) 0.16f else 0.09f))
                            .hoverable(interaction)
                            .pressable({ onPlay(index) }, interaction = interaction, pressScale = 0.98f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.convx.desktop.ui.components.Artwork(
                            song.thumbnail,
                            Modifier.width(56.dp).fillMaxHeight(),
                            size = 160,
                            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                            iconSize = 20.dp,
                        )
                        Text(
                            song.title,
                            style = ConvxType.BodyStrong,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
                        )
                    }
                }
                repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Mood and genre tiles: the stripe colour down the left, as on the phone. */
@Composable
fun MoodGrid(title: String, moods: List<MoodAndGenres.Item>) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = Gutter))
        Spacer(Modifier.height(10.dp))
        val bleed = LocalPageInsets.current.start
        LazyHorizontalGrid(
            rows = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = bleed + Gutter, end = Gutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.bleedStart(bleed).fillMaxWidth().height(60.dp * 3 + 20.dp),
        ) {
            items(moods, key = { it.title }) { mood ->
                MoodTile(mood) { navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) }
            }
        }
    }
}

@Composable
fun MoodTile(mood: MoodAndGenres.Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val stripe = Color(mood.stripeColor)
    Box(
        modifier
            .width(196.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(stripe.copy(alpha = if (hovered) 0.55f else 0.38f), Color.White.copy(alpha = 0.06f))))
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.97f),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
        Text(
            mood.title,
            style = ConvxType.BodyStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 10.dp),
        )
    }
}
