package com.kolnovel.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.HomePage
import com.kolnovel.reader.data.LatestEntry
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelSummary
import kotlinx.coroutines.delay

private object HomeCache {
    var page: HomePage? = null
}

@Composable
fun HomeScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var page by remember { mutableStateOf(HomeCache.page) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (page != null && reload == 0) return@LaunchedEffect
        error = null
        try {
            page = services.source.home().also { HomeCache.page = it }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val history = LibraryStore.entries.values.filter { it.lastReadAt > 0 && it.lastChapterUrl != null }
        .sortedByDescending { it.lastReadAt }.take(12)

    val homeState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = homeState, contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 20.dp)) {
            val p = page
            if (p != null && p.featured.isNotEmpty()) item { Featured(p.featured) }
            if (history.isNotEmpty()) {
                item { SectionTitle("تابع القراءة") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(history, key = { it.url }) { e ->
                            val novel = NovelSummary(e.url, e.title, e.cover)
                            ContinueCard(novel, e.lastChapterTitle.orEmpty(), readingProgress(e)) {
                                services.nav.go(Screen.Reader(novel, e.lastChapterUrl!!, e.lastParagraph))
                            }
                        }
                    }
                }
            }
            when {
                p == null && error != null -> item { ErrorBox(error!!, onRetry = { reload++ }) }
                p == null -> item { Loading() }
                else -> {
                    if (p.popularToday.isNotEmpty()) {
                        item { SectionTitle("رائج اليوم") }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(p.popularToday, key = { it.url }) { n ->
                                    NovelCard(n, onClick = { services.nav.go(Screen.Details(n)) }, modifier = Modifier.width(128.dp))
                                }
                            }
                        }
                    }
                    item {
                        SectionTitle("أخر التحديثات") {
                            GlassButton("تحديث", Icons.Filled.Refresh, onClick = { reload++ })
                        }
                    }
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = (maxWidth / 360.dp).toInt().coerceIn(1, 4)
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                p.latest.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { UpdateCard(it, Modifier.weight(1f)) }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FastScrollbar(homeState)
    }
}

/** Big banner of the site's featured novels: blurred cover behind, glass card in front. */
@Composable
private fun Featured(items: List<NovelSummary>) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items) {
        while (true) {
            delay(7000)
            index = (index + 1) % items.size
        }
    }
    val novel = items[index % items.size]
    Box(
        Modifier.fillMaxWidth().height(220.dp).padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).clickable { services.nav.go(Screen.Details(novel)) },
    ) {
        RemoteImage(novel.cover, Modifier.fillMaxSize().blur(28.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(colors.background.copy(alpha = 0.2f), colors.background.copy(alpha = 0.85f)))
            )
        )
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.glass(colors, RoundedCornerShape(18.dp)).padding(6.dp)) {
                RemoteImage(novel.cover, Modifier.height(170.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Pill("مميزة", colors.accent, colors.onAccent)
                Gap(8)
                Text(novel.title, color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                novel.rating?.let { Gap(4); RatingTag(it) }
                novel.excerpt?.let {
                    Gap(8)
                    Text(it, color = colors.text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
                Gap(12)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { i, _ ->
                        Box(
                            Modifier.size(if (i == index) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i == index) colors.accent else colors.muted.copy(alpha = 0.5f))
                                .clickable { index = i },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(novel: NovelSummary, chapter: String, progress: Pair<Int, Int>?, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.width(250.dp).glass(colors, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(novel.cover, Modifier.height(74.dp).aspectRatio(0.7f).clip(RoundedCornerShape(11.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(novel.title, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(chapter, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            progress?.let { ReadingProgress(it.first, it.second, Modifier.padding(top = 4.dp)) }
        }
        Icon(Icons.Filled.PlayArrow, null, tint = colors.accent, modifier = Modifier.size(26.dp))
    }
}

/** One "latest updates" entry: cover + title + the newest chapters, all in one glass card. */
@Composable
private fun UpdateCard(entry: LatestEntry, modifier: Modifier) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    Row(modifier.glass(colors, RoundedCornerShape(18.dp)).padding(7.dp)) {
        RemoteImage(
            entry.novel.cover,
            Modifier.height(112.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)).clickable { services.nav.go(Screen.Details(entry.novel)) },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { services.nav.go(Screen.Details(entry.novel)) },
            )
            Gap(4)
            entry.chapters.take(3).forEach { ch ->
                val read = LibraryStore.entries[entry.novel.url]?.readChapters?.contains(ch.url) == true
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { services.nav.go(Screen.Reader(entry.novel, ch.url)) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (read) Color.Transparent else colors.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ch.title, color = if (read) colors.muted else colors.text, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    ch.time?.let { Text(it, color = colors.muted, fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
    }
}
