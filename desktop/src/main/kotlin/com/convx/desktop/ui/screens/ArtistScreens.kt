/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ItemActions
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.link
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberHeroColors
import com.convx.desktop.ui.components.rememberImage
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.TopBarHeight
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.YouTube
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArtistScreen(browseId: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = LocalPlayback.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val page = rememberPage("artist:$browseId") { YouTube.artist(browseId).getOrThrow() }
    val hero by rememberHeroColors(page.value?.artist?.thumbnail)
    val tint = hero?.background ?: Color(0xFF2C2C2E)
    val listState = rememberLazyListState()
    var expandedSongs by remember { mutableStateOf(false) }

    PageContent(page, Modifier.padding(top = TopBarHeight)) { data ->
        val artist = data.artist
        val followed = library.artists.any { it.id == artist.id }
        val songSection = data.sections.firstOrNull { section -> section.items.isNotEmpty() && section.items.all { it is SongItem } }
        val topSongs = remember(songSection) { songSection?.items?.filterIsInstance<SongItem>()?.map { it.toSong() }.orEmpty() }
        val isThisPlaying = playback.isPlaying && topSongs.any { it.id == playback.currentId }

        PageList(state = listState, tint = tint, topPadding = 0.dp) {
            item(key = "hero") {
                val image by rememberImage(artworkUrl(artist.thumbnail, 1200))
                Box(Modifier.fillMaxWidth().height(380.dp).background(tint)) {
                    image?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)),
                        ),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Gutter, vertical = 22.dp)) {
                        Text(
                            artist.title,
                            style = ConvxType.Hero.copy(fontSize = if (artist.title.length > 22) 48.sp else 72.sp, lineHeight = if (artist.title.length > 22) 52.sp else 76.sp),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val listeners = data.monthlyListenerCount ?: data.subscriberCountText
                        if (!listeners.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(listeners, style = ConvxType.Body, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
            item(key = "actions") {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent)))) {
                    Row(
                        Modifier.padding(horizontal = Gutter, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PlayButton(
                            isThisPlaying,
                            {
                                when {
                                    isThisPlaying -> PlayerController.pause()
                                    artist.playEndpoint != null -> PlayerController.playEndpoint(artist.playEndpoint!!, artist.title)
                                    topSongs.isNotEmpty() -> PlayerController.playSongs(topSongs, 0, artist.title, shuffle = false)
                                    else -> ItemActions.play(artist)
                                }
                            },
                            size = 58.dp,
                            color = LocalAccent.current,
                            glyph = Color.White,
                        )
                        IconButton("shuffle", { ItemActions.play(artist, shuffle = true) }, size = 44.dp, iconSize = 26.dp, tooltip = "Shuffle")
                        PillButton(
                            if (followed) "Following" else "Follow",
                            {
                                Library.toggleArtist(artist.toSaved())
                                Overlays.toast(if (followed) "Unfollowed ${artist.title}" else "Following ${artist.title}")
                            },
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        )
                        val menu = rememberMenuAnchor()
                        Box {
                            IconButton("more_horiz", { menu.expanded = true }, size = 44.dp, iconSize = 26.dp, tooltip = "More options")
                            AppMenu(menu) {
                                if (artist.radioEndpoint != null) {
                                    MenuItem("Start artist radio", "radio") {
                                        ItemActions.radio(artist)
                                        menu.expanded = false
                                    }
                                }
                                MenuItem("Copy link", "link") {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(artist.link()))
                                    Overlays.toast("Link copied", "link")
                                    menu.expanded = false
                                }
                            }
                        }
                    }
                }
            }

            if (songSection != null && topSongs.isNotEmpty()) {
                item(key = "popular") {
                    SectionHeader(
                        songSection.title.ifBlank { "Popular" },
                        Modifier.padding(horizontal = Gutter, vertical = 6.dp),
                        onAction = songSection.moreEndpoint?.let { more -> { navigator.navigate(Screen.ArtistItems(more.browseId, more.params, "${artist.title}: ${songSection.title}")) } },
                    )
                }
                val shown = if (expandedSongs) topSongs else topSongs.take(5)
                itemsIndexed(shown, key = { i, s -> "pop-$i-${s.id}" }) { i, song ->
                    Box(Modifier.padding(horizontal = Gutter - 12.dp)) {
                        SongRow(song, { PlayerController.playSongs(topSongs, i, artist.title) }, index = i + 1)
                    }
                }
                if (topSongs.size > 5) {
                    item(key = "pop-more") {
                        Text(
                            if (expandedSongs) "Show less" else "See more",
                            style = ConvxType.SubheadStrong,
                            color = ConvxColors.SecondaryLabel,
                            modifier = Modifier
                                .padding(horizontal = Gutter, vertical = 12.dp)
                                .pressable({ expandedSongs = !expandedSongs }, pressScale = 1f),
                        )
                    }
                }
                item(key = "pop-gap") { Spacer(Modifier.height(24.dp)) }
            }

            data.sections.filter { it !== songSection && it.items.isNotEmpty() }.forEachIndexed { index, section ->
                item(key = "sec-$index-${section.title}") {
                    val more = section.moreEndpoint
                    ItemShelf(
                        section.title,
                        section.items,
                        onTitleClick = more?.let { { navigator.navigate(Screen.ArtistItems(it.browseId, it.params, "${artist.title}: ${section.title}")) } },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            data.description?.takeIf { it.isNotBlank() }?.let { about ->
                item(key = "about") {
                    Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp).widthIn(max = 860.dp)) {
                        Text("About", style = ConvxType.Title2)
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(ConvxShapes.CardLarge).background(Color.White.copy(alpha = 0.07f)).padding(22.dp),
                        ) {
                            val listeners = data.monthlyListenerCount ?: data.subscriberCountText
                            if (!listeners.isNullOrBlank()) {
                                Text(listeners, style = ConvxType.Headline)
                                Spacer(Modifier.height(10.dp))
                            }
                            Text(about, style = ConvxType.Body, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

/** Everything in one of an artist's sections: all songs, all albums, all singles... */
@Composable
fun ArtistItemsScreen(browseId: String, params: String?, title: String) {
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }
    val page = rememberPage("artist-items:$browseId:$params") { YouTube.artistItems(BrowseEndpoint(browseId, params)).getOrThrow() }
    PageList(
        onNearEnd = {
            val continuation = page.value?.continuation ?: return@PageList
            if (loadingMore) return@PageList
            loadingMore = true
            scope.launch {
                val more = withContext(Dispatchers.IO) { YouTube.artistItemsContinuation(continuation).getOrNull() }
                if (more != null) page.update { it.copy(items = it.items + more.items, continuation = more.continuation) }
                loadingMore = false
            }
        },
    ) {
        item(key = "title") {
            Text(page.value?.title?.ifBlank { null } ?: title, style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        val data = page.value
        when {
            data != null -> {
                val items = data.items.distinctBy { it.id }
                val songs = items.filterIsInstance<SongItem>()
                if (items.isNotEmpty() && songs.size == items.size) {
                    val mapped = songs.map { it.toSong() }
                    songTable(mapped, title, "ai")
                } else {
                    item(key = "grid") {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items.forEach { ItemCard(it) }
                        }
                    }
                }
                if (loadingMore) item { LoadingState() }
            }
            page.error != null -> item { MessageState("Couldn't load this page", friendlyError(page.error), actionText = "Try again", onAction = { page.retry() }) }
            else -> item { LoadingState() }
        }
    }
}
