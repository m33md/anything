package com.kolnovel.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.ChapterRef
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelDetails
import com.kolnovel.reader.data.NovelSummary
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

private object DetailsCache {
    val map = object : LinkedHashMap<String, NovelDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovelDetails>?) = size > 20
    }
}

fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(novel: NovelSummary) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var details by remember(novel.url) { mutableStateOf(DetailsCache.map[novel.url]) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(novel.url, reload) {
        error = null
        try {
            val d = services.source.details(novel.url)
            DetailsCache.map[novel.url] = d
            details = d
            LibraryStore.noteChapterCount(novel.url, d.chapters.size)
        } catch (e: Exception) {
            if (details == null) error = e.message ?: e.toString()
        }
    }
    val entry = LibraryStore.entries[novel.url]
    LaunchedEffect(novel.url) { LibraryStore.clearNew(novel.url) }

    val d = details
    val summary = if (d != null) novel.copy(title = d.title.ifBlank { novel.title }, cover = d.cover ?: novel.cover) else novel
    var newestFirst by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var synopsisOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        // Blurred cover across the top, fading into the background.
        RemoteImage(summary.cover, Modifier.fillMaxWidth().height(360.dp).blur(40.dp))
        Box(
            Modifier.fillMaxWidth().height(362.dp).background(
                Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.35f), colors.background))
            )
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 30.dp)) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.glass(colors, RoundedCornerShape(20.dp)).padding(7.dp)) {
                        RemoteImage(summary.cover, Modifier.width(220.dp).aspectRatio(0.7f).clip(RoundedCornerShape(14.dp)))
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(summary.title, color = colors.text, fontWeight = FontWeight.Black, fontSize = 28.sp)
                        d?.altTitle?.let { Text(it, color = colors.muted, fontSize = 15.sp) }
                        Gap(8)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            d?.status?.let { Pill(it, colors.accent, colors.onAccent) }
                            (d?.rating ?: novel.rating)?.let { RatingTag(it) }
                            d?.let { Text("${it.chapters.size} فصل", color = colors.muted, fontSize = 13.sp) }
                        }
                        Gap(12)
                        if (d != null) {
                            GlassPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
                                d.info.forEach { (k, v) ->
                                    Row(Modifier.padding(vertical = 2.dp)) {
                                        Text("$k: ", color = colors.muted, fontSize = 14.sp)
                                        Text(v, color = colors.text, fontSize = 14.sp)
                                    }
                                }
                                if (d.genres.isNotEmpty()) {
                                    Gap(8)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        d.genres.forEach { g -> GlassChip(g) }
                                    }
                                }
                            }
                        }
                        Gap(12)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d != null && d.chapters.isNotEmpty()) {
                                val resume = entry?.lastChapterUrl
                                if (resume != null) {
                                    AccentButton("تابع: ${entry.lastChapterTitle.orEmpty()}".take(40), Icons.Filled.PlayArrow, {
                                        services.nav.go(Screen.Reader(summary, resume, entry.lastParagraph))
                                    })
                                    GlassButton("من البداية", onClick = { services.nav.go(Screen.Reader(summary, d.chapters.first().url)) })
                                } else {
                                    AccentButton("ابدأ القراءة", Icons.Filled.PlayArrow, {
                                        services.nav.go(Screen.Reader(summary, d.chapters.first().url))
                                    })
                                }
                            }
                            val inLib = entry?.inLibrary == true
                            GlassButton(
                                if (inLib) "في مكتبتي" else "أضف لمكتبتي",
                                if (inLib) Icons.Filled.Check else Icons.Filled.Add,
                                onClick = { LibraryStore.toggleLibrary(summary) },
                                tint = if (inLib) colors.accent else null,
                            )
                            GlassButton(
                                "مفضلة",
                                if (entry?.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                onClick = { LibraryStore.toggleFavorite(summary) },
                                tint = if (entry?.favorite == true) colors.accent else null,
                            )
                            if (d != null) DownloadButton(summary, d)
                            GlassButton("افتح في الموقع", Icons.Filled.Share, onClick = { openInBrowser(novel.url) })
                            GlassButton("تحديث", Icons.Filled.Refresh, onClick = { reload++ })
                        }
                    }
                }
            }
            if (d == null) {
                item { if (error != null) ErrorBox(error!!, onRetry = { reload++ }) else Loading() }
                return@LazyColumn
            }
            if (d.synopsis.isNotEmpty()) {
                item {
                    SectionTitle("القصة")
                    GlassPanel(
                        Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable { synopsisOpen = !synopsisOpen }
                    ) {
                        val shown = if (synopsisOpen) d.synopsis else d.synopsis.take(2)
                        shown.forEach {
                            Text(it, color = colors.text, fontSize = 15.sp, lineHeight = 26.sp, modifier = Modifier.padding(vertical = 3.dp))
                        }
                        if (d.synopsis.size > 2) {
                            Text(if (synopsisOpen) "إخفاء" else "عرض المزيد", color = colors.accent, fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                val readCount = d.chapters.count { entry?.readChapters?.contains(it.url) == true }
                SectionTitle("الفصول (${d.chapters.size})") {
                    Text("قرأت $readCount", color = colors.muted, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    GlassChip(if (newestFirst) "الأحدث أولاً" else "الأقدم أولاً") { newestFirst = !newestFirst }
                    Spacer(Modifier.width(6.dp))
                    ChapterMenu(summary, d)
                }
                Row(
                    Modifier.fillMaxWidth().glass(colors, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (filter.isEmpty()) Text("ابحث برقم أو عنوان الفصل...", color = colors.muted, fontSize = 14.sp)
                        BasicTextField(
                            filter, { filter = it }, singleLine = true,
                            textStyle = TextStyle(color = colors.text, fontSize = 14.sp, fontFamily = Cairo),
                            cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (filter.isNotEmpty()) {
                        Icon(Icons.Filled.Close, null, tint = colors.muted, modifier = Modifier.size(18.dp).clickable { filter = "" })
                    }
                }
                Gap(8)
            }
            val ordered = if (newestFirst) d.chapters.asReversed() else d.chapters
            val shown = if (filter.isBlank()) ordered else ordered.filter {
                it.number.contains(filter.trim()) || it.title?.contains(filter.trim()) == true
            }
            items(shown, key = { it.url }) { ch ->
                ChapterRow(ch, read = entry?.readChapters?.contains(ch.url) == true, current = entry?.lastChapterUrl == ch.url) {
                    services.nav.go(Screen.Reader(summary, ch.url, if (entry?.lastChapterUrl == ch.url) entry.lastParagraph else 0))
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(ch: ChapterRef, read: Boolean, current: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .then(if (current) Modifier.glass(colors, RoundedCornerShape(12.dp), strong = true) else Modifier.glass(colors, RoundedCornerShape(12.dp)))
            .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.accent))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            ch.number, color = if (read) colors.muted else colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            ch.title.orEmpty(), color = if (read) colors.muted else colors.text, fontSize = 14.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (ChapterStore.has(ch.url)) {
            Icon(Icons.Filled.CheckCircle, "محفوظ", tint = colors.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (read) {
            Icon(Icons.Filled.Done, "مقروء", tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        ch.date?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
    }
}

@Composable
private fun DownloadButton(novel: NovelSummary, d: NovelDetails) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val p = services.downloads.progress[novel.url]
    val running = services.downloads.isRunning(novel.url) && p != null && p.done + p.failed < p.total
    var menu by remember { mutableStateOf(false) }
    Box {
        if (running) {
            Column(
                Modifier.width(200.dp).glass(colors, RoundedCornerShape(12.dp))
                    .clickable { services.downloads.cancel(novel.url) }.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("يحفظ ${p!!.done}/${p.total} (اضغط للإيقاف)", color = colors.text, fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else (p.done + p.failed).toFloat() / p.total },
                    color = colors.accent, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        } else {
            GlassButton(
                if (p != null && p.total > 0) "تم الحفظ (${p.done}/${p.total})" else "حفظ للقراءة بدون نت",
                Icons.Filled.KeyboardArrowDown, onClick = { menu = true },
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            val entry = LibraryStore.entries[novel.url]
            val unread = d.chapters.filter { entry?.readChapters?.contains(it.url) != true }
            DropdownMenuItem({ Text("الفصول غير المقروءة (${unread.size})") }, onClick = {
                menu = false; services.downloads.start(novel.url, novel.title, unread)
            })
            DropdownMenuItem({ Text("أول 10 غير مقروءة") }, onClick = {
                menu = false; services.downloads.start(novel.url, novel.title, unread.take(10))
            })
            DropdownMenuItem({ Text("كل الفصول (${d.chapters.size})") }, onClick = {
                menu = false; services.downloads.start(novel.url, novel.title, d.chapters)
            })
            DropdownMenuItem({ Text("حذف الفصول المحفوظة") }, onClick = {
                menu = false; services.downloads.cancel(novel.url); ChapterStore.delete(d.chapters.map { it.url })
            })
        }
    }
}

@Composable
private fun ChapterMenu(novel: NovelSummary, d: NovelDetails) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassChip("تحديد...") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem({ Text("تحديد الكل كمقروء") }, onClick = {
                open = false
                LibraryStore.edit(novel.url, novel.title, novel.cover) { it }
                LibraryStore.markRead(novel.url, d.chapters.map { it.url }, true)
            })
            DropdownMenuItem({ Text("تحديد الكل كغير مقروء") }, onClick = {
                open = false
                LibraryStore.markRead(novel.url, d.chapters.map { it.url }, false)
            })
            val last = LibraryStore.entries[novel.url]?.lastChapterUrl
            val idx = d.chapters.indexOfFirst { it.url == last }
            if (idx > 0) {
                DropdownMenuItem({ Text("تحديد ما قبل الفصل الحالي كمقروء") }, onClick = {
                    open = false
                    LibraryStore.markRead(novel.url, d.chapters.take(idx).map { it.url }, true)
                })
            }
        }
    }
}
