package com.kolnovel.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.kolnovel.reader.data.DownloadJob
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelStore
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SavedNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SavedEntry(val novel: SavedNovel, val chapters: Int, val bytes: Long, val firstSaved: String?)

/** The queue (what is downloading now) and every novel with chapters saved on the phone. */
@Composable
fun DownloadsScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val version = ChapterStore.version
    val novelsVersion = NovelStore.version
    val saved by produceState<List<SavedEntry>?>(null, version, novelsVersion) {
        value = withContext(Dispatchers.IO) {
            NovelStore.all().mapNotNull { n ->
                val urls = n.chapters.map { it.url }.filter { ChapterStore.has(it) }
                if (urls.isEmpty()) null else SavedEntry(n, urls.size, ChapterStore.sizeOf(urls), urls.firstOrNull())
            }.sortedByDescending { it.novel.savedAt }
        }
    }
    var toDelete by remember { mutableStateOf<SavedEntry?>(null) }
    val jobs = Downloads.jobs

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Downloads.notice?.let { notice ->
                item {
                    GlassPanel(Modifier.fillMaxWidth(), strong = true, padding = 12.dp) {
                        Text(notice, color = colors.text, fontSize = 14.sp, lineHeight = 22.sp)
                        Gap(8)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccentButton("استئناف", Icons.Filled.PlayArrow, { Downloads.resume() })
                            if (notice == KolSource.CLOUDFLARE_MESSAGE) {
                                GlassButton("تحقق من الموقع", onClick = { services.nav.go(Screen.Verify) })
                            }
                        }
                    }
                }
            }
            if (jobs.isNotEmpty()) {
                item {
                    SectionTitle("قائمة التنزيل") {
                        if (Downloads.hasPending) {
                            if (Downloads.paused) AccentButton("استئناف الكل", Icons.Filled.PlayArrow, { Downloads.resume() })
                            else GlassButton("إيقاف مؤقت", KolIcons.Pause, onClick = { Downloads.pause() })
                        }
                        if (jobs.any { it.finished }) {
                            Spacer(Modifier.width(6.dp))
                            GlassChip("مسح المكتمل") { Downloads.clearFinished() }
                        }
                    }
                }
                items(jobs, key = { "job:" + it.novelUrl }) { job -> JobCard(job) }
            }
            item { SectionTitle("محفوظ على الجهاز") }
            val list = saved
            when {
                list == null -> item { Loading() }
                list.isEmpty() -> item {
                    GlassPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Text("لا توجد فصول محفوظة بعد.", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Gap(4)
                        Text(
                            "افتح أي رواية واضغط \"الرواية كاملة\" أو \"نطاق فصول\"، أو زر التنزيل بجانب أي فصل. " +
                                "الفصول المحفوظة تُقرأ بدون إنترنت.",
                            color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp,
                        )
                    }
                }
                else -> items(list, key = { "saved:" + it.novel.url }) { e ->
                    SavedCard(
                        e,
                        onOpen = { services.nav.go(Screen.Details(NovelSummary(e.novel.url, e.novel.title, e.novel.cover))) },
                        onRead = {
                            val summary = NovelSummary(e.novel.url, e.novel.title, e.novel.cover)
                            val last = LibraryStore.entries[e.novel.url]
                            val url = last?.lastChapterUrl ?: e.firstSaved
                            if (url != null) services.nav.go(Screen.Reader(summary, url, if (url == last?.lastChapterUrl) last.lastParagraph else 0))
                        },
                        onDelete = { toDelete = e },
                    )
                }
            }
        }
        FastScrollbar(listState)
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف \"${e.novel.title}\" من الجهاز؟") },
            text = { Text("سيتم حذف ${e.chapters} فصل محفوظ. يمكنك تنزيلها مرة أخرى لاحقًا.") },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.cancel(e.novel.url)
                    ChapterStore.delete(e.novel.chapters.map { it.url })
                    toDelete = null
                }) { Text("حذف", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("إلغاء", color = colors.text) } },
            containerColor = colors.spec.backgroundAlt,
            titleContentColor = colors.text,
            textContentColor = colors.text,
        )
    }
}

@Composable
private fun JobCard(job: DownloadJob) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val handled = job.done + job.failed.size
    val isCurrent = Downloads.running && Downloads.jobs.firstOrNull { !it.finished }?.novelUrl == job.novelUrl
    Row(
        Modifier.fillMaxWidth().glass(colors, RoundedCornerShape(16.dp))
            .clickable { services.nav.go(Screen.Details(NovelSummary(job.novelUrl, job.title, job.cover))) }
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(job.cover, Modifier.height(78.dp).aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = when {
                job.finished && job.failed.isEmpty() -> "اكتمل: ${job.done} فصل"
                job.finished -> "انتهى: ${job.done} محفوظ، تعذر ${job.failed.size}"
                isCurrent -> "يتم التنزيل $handled/${job.total}" + (Downloads.current?.let { " • ${it.number}" } ?: "")
                Downloads.paused -> "متوقف $handled/${job.total}"
                else -> "في الانتظار $handled/${job.total}"
            }
            Text(state, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { if (job.total == 0) 1f else handled.toFloat() / job.total },
                color = colors.accent, trackColor = colors.glassFillStrong,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        if (job.finished && job.failed.isNotEmpty()) {
            IconBtn(Icons.Filled.Refresh, "إعادة المحاولة") { Downloads.retryFailed(job.novelUrl) }
        }
        IconBtn(Icons.Filled.Close, if (job.finished) "إخفاء" else "إلغاء") { Downloads.cancel(job.novelUrl) }
    }
}

@Composable
private fun SavedCard(e: SavedEntry, onOpen: () -> Unit, onRead: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().glass(colors, RoundedCornerShape(16.dp)).clickable(onClick = onOpen).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(e.novel.cover, Modifier.height(86.dp).aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${e.chapters} من ${e.novel.chapters.size} فصل  •  %.1f م.ب".format(e.bytes / 1_048_576.0),
                color = colors.muted, fontSize = 12.sp,
            )
            LibraryStore.entries[e.novel.url]?.lastChapterTitle?.let {
                Text("آخر قراءة: $it", color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconBtn(Icons.Filled.PlayArrow, "اقرأ", onClick = onRead)
        IconBtn(Icons.Filled.Delete, "حذف", onClick = onDelete)
    }
}
