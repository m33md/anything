package com.kolnovel.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.SavedNovel
import com.kolnovel.reader.data.SavedNovels

/** Running downloads and every novel with chapters saved for reading without internet. */
@Composable
fun DownloadsScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val downloads = services.downloads
    val ids = ChapterStore.savedIds
    val running = downloads.progress.values.toList()
    val saved = remember(SavedNovels.entries, ids, downloads.progress) {
        SavedNovels.entries.values
            .map { it to ChapterStore.countSaved(it.chapters.map { c -> c.url }) }
            .filter { (n, count) -> count > 0 || downloads.isRunning(n.url) }
            .sortedByDescending { (n, _) -> n.updatedAt }
    }
    val sizeMb = remember(ids) { ChapterStore.sizeBytes() / 1_048_576.0 }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().dragScroll(listState), state = listState, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 30.dp)) {
            if (running.isNotEmpty()) {
                item {
                    SectionTitle("قيد التحميل") {
                        if (running.any { it.finished }) GlassChip("إخفاء المكتمل") { downloads.clearFinished() }
                    }
                }
                items(running, key = { "p" + it.novel.url }) { p -> DownloadProgressRow(p, downloads) }
            }
            item {
                SectionTitle("محفوظة للقراءة بدون نت") {
                    Text("%.1f ميغابايت".format(sizeMb), color = colors.muted, fontSize = 13.sp)
                }
            }
            if (saved.isEmpty()) {
                item {
                    GlassPanel(Modifier.fillMaxWidth(), padding = 20.dp) {
                        Text("لا توجد فصول محملة بعد.", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Gap(6)
                        Text(
                            "افتح أي رواية واضغط \"تحميل الفصول\" لتحميل الرواية كاملة أو مجموعة فصول، " +
                                "أو اضغط سهم التحميل بجانب أي فصل. بعدها تجدها هنا وتقرأها بدون إنترنت.",
                            color = colors.muted, fontSize = 14.sp, lineHeight = 24.sp,
                        )
                    }
                }
            }
            items(saved, key = { "s" + it.first.url }) { (novel, count) -> SavedRow(novel, count) }
        }
        EdgeScrollbar(rememberScrollbarAdapter(listState))
    }
}

@Composable
fun DownloadProgressRow(p: Downloads.Progress, downloads: Downloads) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(colors, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(p.novel.cover, Modifier.width(46.dp).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = when {
                !p.finished -> "تم ${p.done} من ${p.total}" + (p.current?.let { " · الآن: $it" } ?: "")
                p.failed.isEmpty() -> "اكتمل: ${p.done} فصل"
                else -> "اكتمل: ${p.done} فصل، وتعذر ${p.failed.size}"
            }
            Text(state, color = colors.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(6)
            LinearProgressIndicator(
                progress = { if (p.total == 0) 1f else (p.done + p.failed.size).toFloat() / p.total },
                color = colors.accent, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.failed.isNotEmpty() && p.finished) {
                GlassButton("إعادة الفاشلة", Icons.Filled.Refresh, onClick = { downloads.retryFailed(p.novel.url) })
            }
            if (!p.finished) GlassButton("إيقاف", Icons.Filled.Close, onClick = { downloads.cancel(p.novel.url) })
        }
    }
}

@Composable
private fun SavedRow(novel: SavedNovel, count: Int) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(colors, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(novel.cover, Modifier.width(56.dp).aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val total = novel.chapters.size
            Text(
                if (count >= total) "كل الفصول محفوظة ($total)" else "محفوظ $count من $total فصل",
                color = if (count >= total) colors.accent else colors.muted, fontSize = 13.sp,
            )
            LibraryStore[novel.url]?.lastChapterTitle?.let { Text("آخر قراءة: $it", color = colors.muted, fontSize = 12.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val start = startChapter(novel)
            if (start != null) {
                AccentButton("اقرأ", Icons.Filled.PlayArrow, onClick = {
                    val entry = LibraryStore[novel.url]
                    services.nav.go(Screen.Reader(novel.summary, start, if (entry?.lastChapterUrl == start) entry.lastParagraph else 0))
                })
            }
            GlassButton("الفصول", Icons.AutoMirrored.Filled.List, onClick = { services.nav.go(Screen.Details(novel.summary)) })
            if (confirm) {
                GlassButton("تأكيد الحذف", Icons.Filled.Delete, tint = colors.accent, onClick = {
                    services.downloads.cancel(novel.url)
                    ChapterStore.delete(novel.chapters.map { it.url })
                    SavedNovels.forget(novel.url)
                })
                GlassChip("إلغاء") { confirm = false }
            } else {
                GlassButton("حذف", Icons.Filled.Delete, onClick = { confirm = true })
            }
        }
    }
}

/** Where "read" starts: the chapter the user left off in if it is saved, else the first saved unread one. */
private fun startChapter(novel: SavedNovel): String? {
    val entry = LibraryStore[novel.url]
    val savedChapters = novel.chapters.filter { ChapterStore.has(it.url) }
    entry?.lastChapterUrl?.let { last -> if (savedChapters.any { it.url == last }) return last }
    return (savedChapters.firstOrNull { entry?.readChapters?.contains(it.url) != true } ?: savedChapters.firstOrNull())?.url
}
